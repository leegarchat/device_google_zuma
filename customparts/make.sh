#!/bin/bash

# ============================================================
# Глобальные переменные и Конфигурация
# ============================================================
BAK_ROOT="bakFiles"
ORIG_DIR="$BAK_ROOT/original"
NEW_FILES_LIST="$BAK_ROOT/newFiles.txt" # Файл со списком путей для сохранения
SNAPSHOT_DIR=""

# Цвета
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Флаги состояния
DO_SYNC=false
FORCE_SYNC_ONLY=false # --forcesync
DO_BUILD=false
DO_CLEAN=false
DO_SETUP_ENV=false
FORCE_ENV=false
FORCE_LUNCH=false
SYNC_DRIVE=false
DO_CLEAN_DEVICE=false
# Параметры
JOBS=""
DEVICE_CODE=""
BUILD_TYPE="" # Default: userdebug
APP_TARGET="" # --app
VERSION_PART="bp3a" # --version (default)


# for kernel in "bp3a.251105.015-zuma_A16_Sultan_WKSU_SUSFS-v2.0.0-r2" "bp3a.251105.015-SukiSU-37714-zuma_A16_Sultan-20250909_SUSFS-v2.0.0-r12" "bp3a.251105.015-StockKernel" ; do
#     for d in husky shiba akita ; do 
#         . ./make.sh -j 16 --build $d user --kernel "${d}-bp3a.251105.015-zuma_A16_Sultan_WKSU_SUSFS-v2.0.0-r2" --clean-device --sync-drive
#         . ./make.sh -j 16 --build $d user --kernel "${d}-bp3a.251105.015-SukiSU-37714-zuma_A16_Sultan-20250909_SUSFS-v2.0.0-r12" --clean-device --sync-drive
#         . ./make.sh -j 16 --build $d user --kernel "${d}-bp3a.251105.015-StockKernel" --clean-device --sync-drive
#     done
# done
safe_exit() {
    local code=${1:-1}
    # Проверка: если скрипт запущен через source, используем return
    if [[ "${BASH_SOURCE[0]}" != "${0}" ]]; then
        return "$code" 2>/dev/null || exit "$code"
    else
        exit "$code"
    fi
}

show_help() {
    echo -e "${BLUE}Android Build Wrapper (v2.2)${NC}"
    echo -e "Использование: ${GREEN}source ./make.sh [ОПЦИИ] [DEVICE] [TYPE]${NC}"
    echo ""
    echo -e "${YELLOW}Окружение:${NC}"
    echo -e "  --setup-env      : Установить пакеты и зависимости (apt/dnf/pacman)."
    echo ""
    echo -e "${YELLOW}Синхронизация:${NC}"
    echo -e "  --sync           : Умная синхронизация (Backup -> Revert -> Sync -> Patch)."
    echo -e "  --forcesync      : Просто жесткий 'repo sync' (без патчей и бекапов)."
    echo -e "  -j <num>         : Потоки."
    echo ""
    echo -e "${YELLOW}Ядро (Kernel):${NC}"
    echo -e "  --kernel <dir>   : Указать папку ядра (в device/google/pixel-kernels)."
    echo -e "  --prebuild-kernel: Режим сборки с prebuilt ядрами."
    echo -e "  --ls-kernel      : Показать список доступных ядер."
    echo ""
    echo -e "${YELLOW}Сборка и Выгрузка:${NC}"
    echo -e "  --build          : Активировать сборку."
    echo -e "  --app <name>     : Сбилдить конкретное приложение/модуль (требует --build)."
    echo -e "  --clean          : Полная очистка (m clean)."
    echo -e "  --clean-device   : Очистить только папку устройства (rm -rf out/target/product/DEVICE)."
    echo -e "  --sync-drive     : Авто-выгрузка последнего ZIP и образов на GDrive после успеха."
    echo -e "  --env            : Force source build/envsetup.sh."
    echo -e "  --lunch          : Force lunch."
    echo -e "  --version <ver>  : Версия платформы в lunch (def: bp3a)."
    echo ""
    echo -e "${YELLOW}Аргументы:${NC}"
    echo -e "  DEVICE           : Codename (shiba, stone)."
    echo -e "  TYPE             : user, userdebug, eng (def: userdebug)."
    echo ""
}

# ============================================================
# Функция установки зависимостей
# ============================================================
run_env_setup() {
    echo -e "${BLUE}=== ANDROID BUILD ENV SETUP ===${NC}"

    if [[ $EUID -ne 0 ]]; then
        echo -e "${RED}[ERROR] Для установки пакетов нужны права root (sudo).${NC}"
        return 1
    fi

    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS_ID=$ID
        OS_LIKE=$ID_LIKE
    else
        echo -e "${RED}[ERROR] Не могу определить ОС.${NC}"
        return 1
    fi

    # --- Внутренние функции установки ---
    _install_apt() {
        echo -e "${YELLOW}[APT] Установка зависимостей...${NC}"
        apt-get update
        local DEPS="git git-core gnupg flex bison build-essential zip curl zlib1g-dev \
        gcc-multilib g++-multilib libc6-dev-i386 libncurses5 lib32ncurses5-dev \
        x11proto-core-dev libx11-dev lib32z1-dev libgl1-mesa-dev libxml2-utils \
        xsltproc unzip fontconfig python3 python-is-python3 rsync schedtool \
        libssl-dev bc ccache libncurses6 libncurses-dev libelf-dev \
        imagemagick lzop pngcrush git-lfs openjdk-17-jdk openjdk-21-jdk"
        apt-get install -y $DEPS
        
        if [ ! -f /usr/lib/x86_64-linux-gnu/libncurses.so.5 ]; then
             ln -s /usr/lib/x86_64-linux-gnu/libncurses.so.6 /usr/lib/x86_64-linux-gnu/libncurses.so.5 || true
             ln -s /usr/lib/x86_64-linux-gnu/libtinfo.so.6 /usr/lib/x86_64-linux-gnu/libtinfo.so.5 || true
        fi
    }

    _install_fedora() {
        echo -e "${YELLOW}[DNF] Обнаружена Fedora ($OS_ID). Настройка окружения для Android 16 QPR1...${NC}"
        local DNF_CMD="dnf"
        if command -v dnf5 &> /dev/null; then DNF_CMD="dnf5"; fi

        # Убираем исключение i686 библиотек
        if [ -f /etc/dnf/dnf.conf ] && grep -q "exclude=.*i686" /etc/dnf/dnf.conf; then
            sudo sed -i 's/^exclude=/#exclude=/g' /etc/dnf/dnf.conf
        fi

        sudo $DNF_CMD upgrade --refresh -y
        sudo $DNF_CMD install -y --skip-broken @"Development Tools" @"C Development Tools and Libraries" || true

        # Пакеты из вашего списка + критические дополнения для A16/SM8250
        local PKGS="git-core gnupg flex bison gperf zlib-devel \
        glibc-devel.i686 libstdc++.i686 ncurses-devel.i686 \
        libX11-devel libX11-devel.i686 mesa-libGL-devel.i686 \
        libxml2-devel libxslt-devel openssl-devel \
        zip unzip curl python3 lzop schedtool pngcrush ccache optipng \
        git-lfs perl-Digest-SHA which ncurses-compat-libs \
        clang llvm lld libxcrypt-compat ncurses-devel \
        readline-devel libffi-devel elfutils-libelf-devel \
        bc rsync bzip2 patch hostname perl-FindBin perl-File-Compare"
        
        sudo $DNF_CMD install -y --skip-broken $PKGS
        sudo $DNF_CMD install -y java-21-openjdk-devel || sudo $DNF_CMD install -y java-17-openjdk-devel

        # Фикс для библиотек ncurses (чтобы старые тулзы видели 5-ю версию)
        if [ ! -f /usr/lib64/libncurses.so.5 ]; then
            sudo ln -sf /usr/lib64/libncurses.so.6 /usr/lib64/libncurses.so.5 || true
            sudo ln -sf /usr/lib64/libtinfo.so.6 /usr/lib64/libtinfo.so.5 || true
        fi

        # Фикс для скриптов ядра, которые все еще вызывают 'python' вместо 'python3'
        if ! command -v python &> /dev/null; then
            sudo ln -sf /usr/bin/python3 /usr/bin/python || true
        fi

        ccache -M 80G
        echo -e "${GREEN}Настройка Fedora завершена.${NC}"
    }

    _install_pacman() {
        echo -e "${YELLOW}[PACMAN] Установка...${NC}"
        if ! grep -q "^\[multilib\]" /etc/pacman.conf; then
            echo "[multilib]" >> /etc/pacman.conf
            echo "Include = /etc/pacman.d/mirrorlist" >> /etc/pacman.conf
        fi
        pacman -Sy --noconfirm
        pacman -S --needed --noconfirm base-devel multilib-devel gcc clang git git-lfs \
        cmake ninja python jdk17-openjdk jdk21-openjdk gperf libxml2 unzip zip curl \
        rsync schedtool perl bc lzop imagemagick ncurses lib32-ncurses lib32-readline \
        lib32-zlib libxslt pngcrush
    }

    case "$OS_ID" in
        ubuntu|debian|linuxmint|pop|kali|parrot) _install_apt ;;
        arch|manjaro|endeavouros) _install_pacman ;;
        *fedora*|centos|rhel|almalinux|rocky) _install_fedora ;;
        *) echo -e "${RED}Дистрибутив не распознан авто-установщиком.${NC}"; return 1 ;;
    esac

    if ! command -v repo &> /dev/null; then
        echo -e "${YELLOW}Установка repo...${NC}"
        curl -s https://storage.googleapis.com/git-repo-downloads/repo > /usr/local/bin/repo
        chmod a+x /usr/local/bin/repo
    fi

    if [ -z "$(git config --global user.name)" ]; then
        git config --global user.name "Android Builder"
        git config --global user.email "bot@localhost"
    fi
    git config --global http.postBuffer 524288000
    echo -e "${GREEN}Настройка окружения завершена!${NC}"
}

# ============================================================
# Основная логика (MAIN)
# ============================================================
export TARGET_KERNEL_DIR_EXT=""

KERNEL_BASE_PATH_PIXEL="device/google/pixel-kernels"

function list_available_kernels() {
    if [ -d "$1" ]; then
        echo "================================================="
        echo "📦 Доступные каталоги ядер в $1/:"
        echo "================================================="
        # Ищем только папки (type d) на глубине 1
        find "$1" -maxdepth 1 -mindepth 1 -type d -printf "  - %P\n" | grep -vE "\.git|anykernels"
        echo "================================================="
    else
        echo "❌ Ошибка: Базовая директория ядер '$1' не найдена."
        return 1
    fi
}

main() {
    # Сброс переменных при повторном запуске в той же сессии
    DO_SYNC=false
    FORCE_SYNC_ONLY=false
    DO_BUILD=false
    DO_CLEAN=false
    DO_SETUP_ENV=false
    FORCE_ENV=false
    FORCE_LUNCH=false
    APP_TARGET=""
    export RELEASE_PIXEL_2025_ENABLED="true"
    # Парсинг аргументов
    export TARGET_KERNEL_IMAGES_EXT="0"
    while [[ $# -gt 0 ]]; do
      case $1 in
        --setup-env) DO_SETUP_ENV=true; shift ;;
        --sync)      DO_SYNC=true; shift ;;
        --forcesync) DO_SYNC=true; FORCE_SYNC_ONLY=true; shift ;;
        --build)     DO_BUILD=true; shift ;;
        --clean)     DO_CLEAN=true; shift ;;
        --env)       FORCE_ENV=true; shift ;;
        --lunch)     FORCE_LUNCH=true; shift ;;
        --app)
             if [ -n "$2" ] && [[ "$2" != --* ]]; then
                 APP_TARGET="$2"
                 shift 2
             else
                 echo -e "${RED}Ошибка: --app требует имя приложения (напр. Settings)${NC}"
                 safe_exit 1
             fi
             ;;
        --version)
             if [ -n "$2" ] && [[ "$2" != --* ]]; then
                 VERSION_PART="$2"
                 shift 2
             else
                 echo -e "${RED}Ошибка: --version требует параметр (напр. bp3a)${NC}"
                 safe_exit 1
             fi
             ;;
        -j)          JOBS="-j$2"; shift 2 ;;
        --clean-device)
            DO_CLEAN_DEVICE=true
            shift
            ;;
        --sync-drive)
            SYNC_DRIVE=true
            shift
            ;;
        --prebuild-kernel) export TARGET_KERNEL_IMAGES_EXT=1; shift ;;
        --kernel)
            # 1. Проверка, передан ли параметр
            if [ -z "$2" ]; then
                echo "❌ Ошибка: Для параметра '--kernel' требуется указать имя каталога."
                echo "   Использование: --kernel <ИмяКаталога>"
                list_available_kernels $KERNEL_BASE_PATH_PIXEL
                return 1 
            fi

            KERNEL_EXTENSION="$2"
            KERNEL_FULL_PATH="$KERNEL_BASE_PATH_PIXEL/$KERNEL_EXTENSION"

            # 2. Проверка, существует ли каталог
            if [ -d "$KERNEL_FULL_PATH" ]; then
                export TARGET_KERNEL_DIR_EXT="$KERNEL_EXTENSION"
                echo "✅ Установлен TARGET_KERNEL_DIR_EXT: $TARGET_KERNEL_DIR_EXT"
                shift 2
            else
                echo "❌ Ошибка: Каталог ядра '$KERNEL_FULL_PATH' не найден."
                echo "   Проверьте правильность имени или используйте '--ls-kernel' для списка."
                return 1 
            fi
            ;;

        --ls-kernel)
            list_available_kernels $KERNEL_BASE_PATH_PIXEL
            shift 1
            ;;
        --help)      show_help; safe_exit 0 ; return 0;;
        *)
          if [[ "$DO_BUILD" == "true" && -z "$DEVICE_CODE" ]]; then
            DEVICE_CODE="$1"
          elif [[ "$DO_BUILD" == "true" && -z "$BUILD_TYPE" ]]; then
            BUILD_TYPE="$1"
          else
            echo -e "${RED}Неизвестный параметр: $1${NC}"
            safe_exit 1
          fi
          shift
          ;;
      esac
    done
    if [ "$DO_CLEAN_DEVICE" = true ]; then
        if [ -n "$DEVICE_CODE" ]; then
            echo -e "${YELLOW}--> Очистка out/target/product/$DEVICE_CODE ...${NC}"
            rm -rf "out/target/product/$DEVICE_CODE"
        else
             echo -e "${RED}Ошибка: --clean-device требует указания устройства (codename)!${NC}"
             safe_exit 1
        fi
    fi
    # 1. SETUP ENV
    if [ "$DO_SETUP_ENV" = true ]; then
        run_env_setup
        if [ $? -ne 0 ]; then
            echo -e "${RED}Ошибка настройки окружения.${NC}"
            safe_exit 1
        fi
        if [ "$DO_SYNC" = false ] && [ "$DO_BUILD" = false ]; then
            safe_exit 0
        fi
    fi

    # 2. SYNC
    if [ "$DO_SYNC" = true ]; then
        echo -e "${BLUE}=== SYNC: Start ===${NC}"

        # --- FORCESYNC ---
        if [ "$FORCE_SYNC_ONLY" = true ]; then
             echo -e "${YELLOW}!!! FORCE SYNC MODE !!!${NC}"
             echo -e "Пропуск создания патчей. Только repo sync."
             repo sync -c --force-sync --no-clone-bundle --no-tags $JOBS
             if [ $? -ne 0 ]; then
                echo -e "${RED}Repo sync failed.${NC}"
                safe_exit 1
             fi
        
        # --- SMART SYNC ---
        else
             if [ ! -d "$ORIG_DIR" ]; then
                echo -e "${RED}Ошибка: Папка $ORIG_DIR не найдена!${NC}"
                safe_exit 1
             fi

             local CURRENT_DATE=$(date +%Y%m%d_%H%M%S)
             SNAPSHOT_DIR="$BAK_ROOT/$CURRENT_DATE"
             mkdir -p "$SNAPSHOT_DIR/diff" "$SNAPSHOT_DIR/copy"
             
             # === NEW FILES BACKUP (Custom Feature) ===
             local NEW_FILES_BACKUP_DIR="$SNAPSHOT_DIR/new_files_temp"
             if [ -f "$NEW_FILES_LIST" ]; then
                echo -e "${YELLOW}--> Резервное копирование новых файлов (из $NEW_FILES_LIST)...${NC}"
                mkdir -p "$NEW_FILES_BACKUP_DIR"
                
                # Читаем файл построчно, убираем пустые строки и пробелы
                grep -v '^[[:space:]]*$' "$NEW_FILES_LIST" | while read -r raw_line; do
                    # Конвертация Windows путей (\) в Linux (/) и удаление пробелов
                    local file_path=$(echo "$raw_line" | tr '\\' '/' | xargs)
                    
                    if [ -e "$file_path" ]; then
                        # Определяем целевую папку в бэкапе, сохраняя структуру
                        local dest_dir="$NEW_FILES_BACKUP_DIR/$(dirname "$file_path")"
                        mkdir -p "$dest_dir"
                        
                        # Перемещаем файл/папку
                        mv "$file_path" "$dest_dir/"
                        echo "Saved & Moved: $file_path"
                    else
                        echo -e "${YELLOW}[SKIP] Файл не найден для бэкапа: $file_path${NC}"
                    fi
                done
             fi
             # ==========================================

             echo -e "${YELLOW}--> Анализ файлов и сброс изменений (git checkout)...${NC}"
             
             local FILES=$(find "$ORIG_DIR" -type f | sed "s|$ORIG_DIR/||")

             for REL_PATH in $FILES; do
                 local REAL_PATH_IN_TREE="$REL_PATH"
                 local FULL_ORIG_PATH="$ORIG_DIR/$REL_PATH"
                 
                 if [ -f "$REAL_PATH_IN_TREE" ]; then
                     # Бекап
                     mkdir -p "$(dirname "$SNAPSHOT_DIR/copy/$REL_PATH")"
                     cp "$REAL_PATH_IN_TREE" "$SNAPSHOT_DIR/copy/$REL_PATH"
                     
                     # Diff
                     mkdir -p "$(dirname "$SNAPSHOT_DIR/diff/$REL_PATH")"
                     diff -u "$FULL_ORIG_PATH" "$REAL_PATH_IN_TREE" > "$SNAPSHOT_DIR/diff/$REL_PATH.patch"
                     
                     if [ ! -s "$SNAPSHOT_DIR/diff/$REL_PATH.patch" ]; then
                        rm "$SNAPSHOT_DIR/diff/$REL_PATH.patch"
                     else
                        echo -e "Patch created: $REL_PATH"
                     fi

                     # Revert через git checkout
                     local FILE_DIR=$(dirname "$REAL_PATH_IN_TREE")
                     local FILE_NAME=$(basename "$REAL_PATH_IN_TREE")
                     
                     if pushd "$FILE_DIR" > /dev/null; then
                         git checkout HEAD -- "$FILE_NAME" 2>/dev/null
                         if [ $? -ne 0 ]; then
                             echo -e "${RED}Checkout failed for $REL_PATH. Fallback to cp.${NC}"
                             cp "$FULL_ORIG_PATH" "$REAL_PATH_IN_TREE"
                         fi
                         popd > /dev/null
                     fi
                 fi
             done

             echo -e "${YELLOW}--> Repo Sync $JOBS ...${NC}"
             repo sync -c --force-sync --no-clone-bundle --no-tags $JOBS
             if [ $? -ne 0 ]; then
                 echo -e "${RED}Sync failed. Changes saved in $SNAPSHOT_DIR${NC}"
                 # Пытаемся вернуть New Files даже при ошибке, чтобы не потерять их
                 if [ -d "$NEW_FILES_BACKUP_DIR" ]; then
                     echo -e "${YELLOW}Emergency restore of new files...${NC}"
                     cp -rf "$NEW_FILES_BACKUP_DIR/." "./"
                 fi
                 safe_exit 1
             fi

             echo -e "${YELLOW}--> Применение патчей...${NC}"
             for REL_PATH in $FILES; do
                 local REAL_PATH_IN_TREE="$REL_PATH"
                 local FULL_ORIG_PATH="$ORIG_DIR/$REL_PATH"
                 local PATCH_PATH="$SNAPSHOT_DIR/diff/$REL_PATH.patch"

                 if [ -f "$REAL_PATH_IN_TREE" ]; then
                     cp "$REAL_PATH_IN_TREE" "$FULL_ORIG_PATH"
                     
                     if [ -f "$PATCH_PATH" ]; then
                         echo -e "Patching $REL_PATH ..."
                         patch -p0 "$REAL_PATH_IN_TREE" < "$PATCH_PATH"
                         if [ $? -ne 0 ]; then
                             echo -e "${RED}CONFLICT: $REL_PATH${NC} (Check .rej)"
                         else
                             echo -e "${GREEN}OK: $REL_PATH${NC}"
                         fi
                     fi
                 fi
             done

             # === NEW FILES RESTORE (Custom Feature) ===
             if [ -d "$NEW_FILES_BACKUP_DIR" ]; then
                 echo -e "${YELLOW}--> Восстановление новых файлов...${NC}"
                 # Копируем содержимое бэкапа обратно в корень, перезаписывая, если что-то появилось
                 cp -rf "$NEW_FILES_BACKUP_DIR/." "./"
                 
                 # Удаляем временную папку, файлы остаются в SNAPSHOT_DIR/new_files_temp, 
                 # если вдруг захочется посмотреть историю, но здесь мы просто чистим переменную или папку
                 # В данной логике: файлы уже в безопасности внутри $SNAPSHOT_DIR, так что копию можно оставить там.
                 # Но саму папку temp внутри снепшота можно не удалять, пусть останется как история.
                 
                 echo -e "${GREEN}New files restored successfully.${NC}"
             fi
             # ==========================================
        fi
        echo -e "${BLUE}=== SYNC: Done ===${NC}"
    fi

    # 3. BUILD
    if [ "$DO_BUILD" = true ]; then
        
        if [ -n "$APP_TARGET" ] && [ -z "$DEVICE_CODE" ]; then
             echo -e "${RED}Для сборки приложения укажите устройство!${NC}"
             safe_exit 1
        fi

        if [ -z "$DEVICE_CODE" ]; then
            echo -e "${RED}Ошибка: Не указан device codename!${NC}"
            safe_exit 1
        fi
        
        [ -z "$BUILD_TYPE" ] && BUILD_TYPE="userdebug"

        TARGET_LUNCH="lineage_${DEVICE_CODE}-${VERSION_PART}-${BUILD_TYPE}"

        echo -e "${BLUE}=== BUILD PREP: $DEVICE_CODE ($TARGET_LUNCH) ===${NC}"

        # Envsetup
        if [ "$FORCE_ENV" = true ] || [ "$DO_CLEAN" = true ] || ! type -t m > /dev/null; then
            echo -e "${YELLOW}--> source build/envsetup.sh${NC}"
            if [ -f "build/envsetup.sh" ]; then
                source build/envsetup.sh
            else
                echo -e "${RED}Файл build/envsetup.sh не найден!${NC}"
                safe_exit 1
            fi
        fi

        # Lunch
        if [ "$FORCE_LUNCH" = true ] || [[ "$TARGET_PRODUCT" != *"$DEVICE_CODE"* ]] \
        || [[ "$TARGET_BUILD_VARIANT" != "$BUILD_TYPE" ]]; then
            echo -e "${YELLOW}--> lunch $TARGET_LUNCH${NC}"
            lunch "$TARGET_LUNCH"
            if [ $? -ne 0 ]; then
                echo -e "${RED}Lunch failed. Пробую стандартный evolution...${NC}"
                lunch "evolution_${DEVICE_CODE}-${BUILD_TYPE}"
                if [ $? -ne 0 ]; then
                     safe_exit 1
                fi
            fi
        fi

        # Clean
        if [ "$DO_CLEAN" = true ]; then
            echo -e "${YELLOW}--> m clean${NC}"
            m clean
        fi

        # Build Command
        if [ -n "$APP_TARGET" ]; then
            echo -e "${GREEN}=== BUILDING APP: $APP_TARGET ===${NC}"
            m "$APP_TARGET" $JOBS
        else
            echo -e "${GREEN}=== BUILDING ROM ===${NC}"
            m evolution $JOBS
            BUILD_STATUS=$?

            # Если сборка успешна и включена синхронизация
            if [ $BUILD_STATUS -eq 0 ] && [ "$SYNC_DRIVE" = true ]; then
                echo -e "${BLUE}=== Starting Upload to Drive process ===${NC}"
                
                local DATE_STR=$(date +%Y%m%d)
                local SRC_DIR="out/target/product/${DEVICE_CODE}"
                
                # --- АВТОПОИСК ПОСЛЕДНЕГО ZIP ---
                # Сортируем по времени (-t), берем первый (head -1). Ищем только .zip
                local LATEST_ZIP_PATH=$(ls -t "$SRC_DIR"/*.zip 2>/dev/null | head -n 1)
                
                if [ -z "$LATEST_ZIP_PATH" ]; then
                    echo -e "${RED}Error: ZIP file not found in $SRC_DIR${NC}"
                else
                    local ZIP_NAME=$(basename "$LATEST_ZIP_PATH")
                    echo -e "${GREEN}Found build: $ZIP_NAME${NC}"

                    # Формирование путей
                    # Локально: myout/device/{date}{KERNEL_EXTENSION}/
                    local DIR_SUFFIX=""
                    [ -n "$TARGET_KERNEL_DIR_EXT" ] && DIR_SUFFIX="/$(echo "${TARGET_KERNEL_DIR_EXT}" | cut -d'-' -f3-)"
                    
                    local LOCAL_OUT="myout/${DEVICE_CODE}/${DATE_STR}${DIR_SUFFIX}"
                    local LOCAL_BOOT_STAFF="${LOCAL_OUT}/boot_staff"
                    
                    mkdir -p "$LOCAL_BOOT_STAFF"
                    
                    # На Диске: {device}/{date}/{KERNEL_EXTENSION}/
                    local DRIVE_PATH="${DEVICE_CODE}/${DATE_STR}"
                    if [ -n "$TARGET_KERNEL_DIR_EXT" ]; then
                        DRIVE_PATH="${DRIVE_PATH}${DIR_SUFFIX}"
                    fi

                    # 1. Копируем найденный ZIP
                    cp "$LATEST_ZIP_PATH" "$LOCAL_OUT/"
                    echo "Copied ZIP to $LOCAL_OUT"

                    # 2. Копируем Boot Staff (имена стандартные)
                    local IMAGES=("vendor_boot.img" "vendor_kernel_boot.img" "boot.img" "dtbo.img" "init_boot.img")
                    for img in "${IMAGES[@]}"; do
                        if [ -f "$SRC_DIR/$img" ]; then
                            cp "$SRC_DIR/$img" "$LOCAL_BOOT_STAFF/"
                        fi
                    done
                    
                    # 3. Запуск Python скрипта в фоне
                    (
                        # Загрузка ZIP
                        python3 UploadToDrive.py \
                        --secret "Google.json" \
                        --input "$LOCAL_OUT/$ZIP_NAME" \
                        --out "$DRIVE_PATH" \
                        --root_id '160d3KGHPnkksmYJ_ztDXOLXkXkl_jZj-'

                        # Загрузка Boot Staff
                        for img in "${IMAGES[@]}"; do
                            if [ -f "$LOCAL_BOOT_STAFF/$img" ]; then
                                python3 UploadToDrive.py \
                                --secret "Google.json" \
                                --input "$LOCAL_BOOT_STAFF/$img" \
                                --out "$DRIVE_PATH/BootStaff/" \
                                --root_id '160d3KGHPnkksmYJ_ztDXOLXkXkl_jZj-'
                            fi
                        done
                        
                        echo -e "${GREEN}Upload process finished in background.${NC}"
                    ) &

                    echo -e "${YELLOW}Upload task detached to background.${NC}"
                fi
            fi
        fi
                        
    fi
}

# Запуск
main "$@"