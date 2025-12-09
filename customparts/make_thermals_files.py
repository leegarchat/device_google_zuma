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

# --- ОПРЕДЕЛЕНИЕ ПУТЕЙ ---

# 1. Определяем директорию скрипта
if getattr(sys, 'frozen', False):
    DIR_PY_SCRIPT = Path(sys.executable).parent
else:
    DIR_PY_SCRIPT = Path(inspect.getfile(inspect.currentframe())).resolve().parent

# 2. Определяем корень Android исходников (ищем папку vendor вверх по дереву)
def find_android_root(start_path):
    current = start_path
    # Поднимаемся вверх максимум на 6 уровней, ищем папку 'vendor'
    for _ in range(6):
        if (current / "vendor").exists() and (current / "device").exists():
            return current
        if current.parent == current:
            break
        current = current.parent
    return None

ANDROID_ROOT = find_android_root(DIR_PY_SCRIPT)

if not ANDROID_ROOT:
    print("❌ Ошибка: Не удалось найти корень исходников Android (папку vendor).")
    print("   Убедитесь, что скрипт запущен внутри дерева исходников.")
    sys.exit(1)

# Вычисляем путь к скрипту относительно корня Android для Makefile
# Например: device/google/zuma/customparts
try:
    REL_SCRIPT_PATH = DIR_PY_SCRIPT.relative_to(ANDROID_ROOT)
except ValueError:
    print("❌ Ошибка: Скрипт находится вне дерева исходников Android.")
    sys.exit(1)

print(f"📍 Скрипт запущен в: {DIR_PY_SCRIPT}")
print(f"🌳 Корень Android: {ANDROID_ROOT}")
print(f"🔗 Относительный путь для MK: {REL_SCRIPT_PATH}")

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
            # Проверка границ
            chunk_between = content[start_pos:ht_match.start()]
            if chunk_between.count('}') > chunk_between.count('{'):
                continue

            new_array_str = process_hot_threshold_match(ht_match, current_offset)
            replacements.append((ht_match.start(0), ht_match.end(0), f'"HotThreshold":{new_array_str}'))

    # 3. Применяем замены с конца
    replacements.sort(key=lambda x: x[0], reverse=True)
    result_content = content
    
    for start, end, replacement in replacements:
        original_chunk = result_content[start:end]
        colon_index = original_chunk.find(':')
        key_part = original_chunk[:colon_index+1]
        value_part = replacement.split(':', 1)[1]
        final_replacement = key_part + value_part
        result_content = result_content[:start] + final_replacement + result_content[end:]
        
    return result_content

def generate_makefile(files_list):
    mk_path = DIR_PY_SCRIPT / MK_FILENAME
    print(f"📝 Генерация {MK_FILENAME}...")
    
    unique_rules = set()

    for filename in files_list:
        # filename пример: thermal_info_config_soc_medium_shiba.json
        
        # 1. Формируем паттерны
        # Имя файла на диске (источник): REL_SCRIPT_PATH/filename
        # Имя файла в vendor (цель): thermal_info_config_soc_medium.json (без device суффикса)
        
        base_name = filename
        for device in DEVICES:
            base_name = base_name.replace(f"_{device}.json", "")
        
        # src_pattern: device/google/zuma/customparts/thermal_info_config_soc_medium_$(TARGET_DEVICE).json
        src_pattern = f"{REL_SCRIPT_PATH}/CustomThermalProfiles/{base_name}_$(DEVICE_CODENAME).json"
        
        # dst_pattern: vendor/etc/thermal_info_config_soc_medium.json
        dst_pattern = f"$(TARGET_COPY_OUT_VENDOR)/etc/{base_name}.json"
        
        rule = f"    {src_pattern}:{dst_pattern}"
        unique_rules.add(rule)

    sorted_rules = sorted(list(unique_rules))
    
    last_rule = ""
    if sorted_rules:
        last_rule = sorted_rules.pop()
        final_last_rule = last_rule.strip()
    
    with open(mk_path, "w", encoding="utf-8") as mk:
        mk.write("# Auto-generated thermal config copy rules\n")
        mk.write(f"# Generated by script in: {REL_SCRIPT_PATH}\n\n")
        mk.write('PRODUCT_COPY_FILES += \\\n')
        
        for line in sorted_rules:
            mk.write(f"{line} \\\n")
            
        if final_last_rule:
            mk.write(f"    {final_last_rule}\n")
            
    print(f"✅ {MK_FILENAME} успешно создан.")

def main():
    print("--- Запуск генератора кастомных термальных конфигов ---")

    for device in DEVICES:
        # Новый путь к исходнику в вендоре
        # vendor/google/{device}/proprietary/vendor/etc/thermal_info_config.json
        source_rel_path = Path(f"vendor/google/{device}/proprietary/vendor/etc/thermal_info_config.json")
        source_path = ANDROID_ROOT / source_rel_path
        
        if not source_path.exists():
            print(f"⚠️  Файл не найден: {source_path}")
            print(f"   (Ожидался путь относительно корня: {source_rel_path})")
            continue
            
        print(f"📄 Обработка {device} (источник: {source_rel_path})")
        
        with open(source_path, 'r', encoding='utf-8') as f:
            base_content = f.read()

        for soc_name, soc_offset in OFFSETS.items():
            for bat_name, bat_offset in OFFSETS.items():
                
                if soc_name == "stock" and bat_name == "stock":
                    continue
                
                new_content = patch_file_content(
                    base_content, 
                    TARGETS_SOC, soc_offset,
                    TARGETS_BATTERY, bat_offset
                )

                soc_part = f"_soc_{soc_name}" if soc_name != "stock" else ""
                bat_part = f"_battery_{bat_name}" if bat_name != "stock" else ""
                DirThermalName = 'CustomThermalProfiles'
                # Имя файла результата
                target_filename = f"thermal_info_config{soc_part}{bat_part}_{device}.json"
                target_path = f"{DIR_PY_SCRIPT}/{DirThermalName}/{target_filename}"
                sds = DIR_PY_SCRIPT / DirThermalName
                sds.mkdir(parents=True, exist_ok=True)
                with open(target_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                
                generated_files_list.append(target_filename)
                # print(f"  + Создан: {target_filename}") 

    # 2. Создаем Makefile в той же директории
    if generated_files_list:
        generate_makefile(generated_files_list)
    else:
        print("ℹ️ Новых файлов не создано.")

    print("--- Готово! ---")

if __name__ == "__main__":
    main()