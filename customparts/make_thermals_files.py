import json
import os
import re
import inspect
import sys
from pathlib import Path

# --- КОНФИГУРАЦИЯ ---

DEVICES = ["shiba", "husky"]

# Сетка температур (смещения)
OFFSETS = {
    "stock": 0,
    "soft": 5,
    "medium": 9,
    "hard": 15,
    "off": 90
}

TARGETS_SOC = [
    "VIRTUAL-SKIN",
    "VIRTUAL-SKIN-HINT",
    "VIRTUAL-SKIN-CPU-LIGHT-ODPM",
    "VIRTUAL-SKIN-CPU-MID",
    "VIRTUAL-SKIN-CPU-HIGH",
    "VIRTUAL-SKIN-CPU-GPU",
    "VIRTUAL-SKIN-SOC",
    "VIRTUAL-SKIN-GPU",
    "VIRTUAL-SKIN-SPEAKER"
]

TARGETS_BATTERY = [
    "VIRTUAL-SKIN-CHARGE-PERSIST",
    "VIRTUAL-SKIN-CHARGE-WIRED"
]

generated_files_list = []
MK_FILENAME = "PartCustomThermalConfigs.mk"
COMMON_MK_FILENAME = "../device-shusky-common.mk" # Относительно WorkDir

# --- ОПРЕДЕЛЕНИЕ ПУТЕЙ ---
# 1. Определяем директорию скрипта
if getattr(sys, 'frozen', False):
    # Если скрипт запущен как исполняемый файл
    DIR_PY_SCRIPT = Path(sys.executable).parent
else:
    # Если запущен как обычный Python скрипт
    DIR_PY_SCRIPT = Path(inspect.getfile(inspect.currentframe())).resolve().parent

# 2. Определяем WorkDir (DirPyScript/../../shusky/thermal)
WORK_DIR = (DIR_PY_SCRIPT / Path("../../shusky/thermal")).resolve()
CUSTOM_CONFIGS_DIR = WORK_DIR / "CustomConfigs"
BASE_JSON_DIR = WORK_DIR

# --- ФУНКЦИИ ---

def process_hot_threshold_match(match, offset):
    """
    Принимает match объект регулярки и возвращает новую строку с измененными числами.
    """
    original_content = match.group(1) # Содержимое внутри [...]
    items = original_content.split(',')
    new_items = []
    
    for item in items:
        item = item.strip()
        if not item: continue
        
        # Регулярка для поиска чисел (включая отрицательные и float)
        if re.match(r'^-?\d+(\.\d+)?$', item):
            val = float(item)
            new_val = round(val + offset, 1)
            new_items.append(str(new_val))
        else:
            # Важно сохранить кавычки для NaN
            new_items.append(item)
            
    # Формат: ["NaN", 38.0, ...] (с пробелом после запятой)
    return f"[{', '.join(new_items)}]"

def patch_file_content(content, targets_soc, offset_soc, targets_bat, offset_bat):
    """
    Ищет в тексте сенсоры и заменяет их HotThreshold, не трогая остальной текст.
    """
    replacements = []
    name_pattern = re.compile(r'"Name"\s*:\s*"([^"]+)"')
    
    for name_match in name_pattern.finditer(content):
        sensor_name = name_match.group(1)
        start_pos = name_match.end()
        
        current_offset = 0
        if sensor_name in targets_soc:
            current_offset = offset_soc
        elif sensor_name in targets_bat:
            current_offset = offset_bat
        
        if current_offset == 0:
            continue

        # 2. Ищем HotThreshold после этого имени, но до следующего "Name" или конца объекта
        ht_pattern = re.compile(r'"HotThreshold"\s*:\s*\[(.*?)\]', re.DOTALL)
        ht_match = ht_pattern.search(content, pos=start_pos)
        
        if ht_match:
            # Проверка границ (упрощенная, как в оригинале)
            chunk_between = content[start_pos:ht_match.start()]
            if chunk_between.count('}') > chunk_between.count('{'):
                continue

            new_array_str = process_hot_threshold_match(ht_match, current_offset)
            replacements.append((ht_match.start(0), ht_match.end(0), f'"HotThreshold":{new_array_str}'))

    # 3. Применяем замены с конца
    replacements.sort(key=lambda x: x[0], reverse=True)
    result_content = content
    
    for start, end, replacement in replacements:
        # Сохраняем стиль ключа (пробелы) из оригинала
        original_chunk = result_content[start:end]
        colon_index = original_chunk.find(':')
        key_part = original_chunk[:colon_index+1] # "HotThreshold": или "HotThreshold" :
        
        # Достаем чисто массив из замены
        value_part = replacement.split(':', 1)[1]
        
        final_replacement = key_part + value_part
        
        result_content = result_content[:start] + final_replacement + result_content[end:]
        
    return result_content

def generate_makefile(files_list):
    print(f"📝 Генерация {MK_FILENAME} в {WORK_DIR}...")
    
    unique_rules = set()
    last_rule = ""

    for filename in files_list:
        # thermal_info_config_soc_soft_battery_medium_shiba.json
        # 1. Определяем базовое имя
        base_name = filename
        for device in DEVICES:
            base_name = base_name.replace(f"_{device}.json", "")
        
        # 2. Формируем паттерны
        src_pattern = f"{base_name}_$(TARGET_DEVICE).json" # thermal_info_config_soc_soft_battery_medium_$(TARGET_DEVICE).json
        dst_pattern = f"{base_name}.json" # thermal_info_config_soc_soft_battery_medium.json
        
        # 3. Формируем правило
        # Здесь добавляем '\' на конце, чтобы потом удалить его только у последнего
        rule = f"    $(TARGET_VENDOR_THERMAL_CONFIG_PATH)/CustomConfigs/{src_pattern}:$(TARGET_COPY_OUT_VENDOR)/etc/{dst_pattern}"
        unique_rules.add(rule)

    sorted_rules = sorted(list(unique_rules))
    
    # 4. Удаляем '\' у последнего правила
    if sorted_rules:
        # Убираем '\' из последнего правила
        last_rule = sorted_rules.pop()
        final_last_rule = last_rule.replace('\\', '').strip() # Удаляем обратный слэш и пробелы
        
    
    with open(WORK_DIR / MK_FILENAME, "w", encoding="utf-8") as mk:
        mk.write("# Auto-generated thermal config copy rules\n\n")
        mk.write('PRODUCT_COPY_FILES += \\\n')
        
        for line in sorted_rules:
            # Добавляем '\' и перенос строки для всех, кроме последнего
            mk.write(f"{line} \\\n")
            
        if final_last_rule:
            mk.write(f"    {final_last_rule}\n") # Записываем последнее правило без '\'
            
    print(f"✅ {MK_FILENAME} успешно создан ({len(unique_rules)} записей).")

def update_common_mk():
    """
    Добавляет мягкий инклюд в device-shusky-common.mk
    """
    common_mk_path = WORK_DIR / COMMON_MK_FILENAME
    include_line = f"inclide $(TARGET_VENDOR_THERMAL_CONFIG_PATH)/{MK_FILENAME})"
    
    if not common_mk_path.exists():
        print(f"⚠️  Файл {COMMON_MK_FILENAME} не найден по пути {common_mk_path}. Пропускаем обновление.")
        return

    print(f"🛠️  Обновление {COMMON_MK_FILENAME}...")
    
    try:
        with open(common_mk_path, 'r', encoding='utf-8') as f:
            content = f.read()
    except Exception as e:
        print(f"❌ Ошибка чтения {common_mk_path}: {e}")
        return

    # Проверяем, существует ли уже include
    if include_line in content:
        print("ℹ️  Мягкий include уже присутствует. Пропускаем.")
        return

    # Ищем место для вставки
    # Цель: вставить сразу после:
    # $(TARGET_VENDOR_THERMAL_CONFIG_PATH)/thermal_info_config_charge_$(TARGET_DEVICE).json:$(TARGET_COPY_OUT_VENDOR)/etc/thermal_info_config_charge.json
    
    # Регулярка для поиска конца блока PRODUCT_COPY_FILES
    # Ищем thermal_info_config_charge.json и следующую за ним обратную косую черту (\) или конец строки
    anchor_pattern = re.compile(
        r'(\$\(TARGET_VENDOR_THERMAL_CONFIG_PATH\)\/thermal_info_config_charge_\$\(TARGET_DEVICE\)\.json:\$\(TARGET_COPY_OUT_VENDOR\)\/etc\/thermal_info_config_charge\.json\s*\\?)',
        re.DOTALL
    )

    match = anchor_pattern.search(content)
    
    if match:
        insert_point = match.end()
        
        # Добавляем пустую строку, а затем include
        new_content = (
            content[:insert_point] + 
            "\n\n" + # Две пустые строки для чистоты
            include_line + "\n" +
            content[insert_point:]
        )
        
        # Записываем обратно
        with open(common_mk_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
            
        print(f"✅ Мягкий include добавлен в {COMMON_MK_FILENAME}.")
        
    else:
        print("⚠️  Не найдена точка вставки (thermal_info_config_charge.json). Добавьте include вручную.")


def main():
    print("--- Запуск генератора кастомных термальных конфигов ---")
    print(f"Рабочая директория (WorkDir): {WORK_DIR}")
    
    # 1. Создаем директорию для сохранения файлов
    CUSTOM_CONFIGS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Создана/проверена директория для конфигов: {CUSTOM_CONFIGS_DIR}")

    for device in DEVICES:
        source_filename = f"thermal_info_config_{device}.json"
        source_path = BASE_JSON_DIR / source_filename
        
        if not source_path.exists():
            print(f"⚠️  Файл {source_filename} не найден по пути {source_path}! Пропускаем.")
            continue
            
        print(f"📄 Чтение оригинала для {device}: {source_filename}")
        
        with open(source_path, 'r', encoding='utf-8') as f:
            base_content = f.read()

        for soc_name, soc_offset in OFFSETS.items():
            for bat_name, bat_offset in OFFSETS.items():
                
                if soc_name == "stock" and bat_name == "stock":
                    # Если stock/stock, то это оригинал, мы его не генерируем
                    continue
                
                new_content = patch_file_content(
                    base_content, 
                    TARGETS_SOC, soc_offset,
                    TARGETS_BATTERY, bat_offset
                )

                soc_part = f"_soc_{soc_name}" if soc_name != "stock" else ""
                bat_part = f"_battery_{bat_name}" if bat_name != "stock" else ""
                
                target_filename = f"thermal_info_config{soc_part}{bat_part}_{device}.json"
                target_path = CUSTOM_CONFIGS_DIR / target_filename
                
                # Сохраняем текстовый файл в CustomConfigs
                with open(target_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                
                # Сохраняем имя файла (только имя, без пути, для mk)
                generated_files_list.append(target_filename)
                print(f"  ✅ Создан: CustomConfigs/{target_filename}")

    # 2. Создаем Makefile
    if generated_files_list:
        generate_makefile(generated_files_list)
    else:
        print("ℹ️ Новых файлов не создано, Makefile не обновлен.")
        
    # 3. Обновляем device-shusky-common.mk
    update_common_mk()

    print("--- Готово! ---")

if __name__ == "__main__":
    main()