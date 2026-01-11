ifeq ($(TARGET_BOOTLOADER_BOARD_NAME),akita)
    BASE_KERNEL_PATH := device/google/akita-kernels
else
    BASE_KERNEL_PATH := device/google/shusky-kernels
endif

DEFAULT_KERNEL_FOLDER := evolution
DEFAULT_KERNEL_DIR := $(BASE_KERNEL_PATH)/$(DEFAULT_KERNEL_FOLDER)

SELECTED_KERNEL_DIR :=

ifeq ($(strip $(TARGET_KERNEL_DIR_EXT)),)
    SELECTED_KERNEL_DIR := $(DEFAULT_KERNEL_DIR)
else
    CANDIDATE_DIR := $(BASE_KERNEL_PATH)/$(TARGET_KERNEL_DIR_EXT)
    
    ifneq ($(wildcard $(TARGET_KERNEL_DIR_EXT)),)
        SELECTED_KERNEL_DIR := $(TARGET_KERNEL_DIR_EXT)
    else ifneq ($(wildcard $(CANDIDATE_DIR)),)
        SELECTED_KERNEL_DIR := $(CANDIDATE_DIR)
    else
        $(warning 🛑 WARNING: Кастомная папка ядра "$(CANDIDATE_DIR)" НЕ НАЙДЕНА!)
        $(warning ➡️ Сброс на путь по умолчанию: $(DEFAULT_KERNEL_DIR))
        SELECTED_KERNEL_DIR := $(DEFAULT_KERNEL_DIR)
    endif
endif

TARGET_KERNEL_DIR := $(SELECTED_KERNEL_DIR)
$(warning ⚙️ KERNEL: Device: $(TARGET_BOOTLOADER_BOARD_NAME) | Path: $(TARGET_KERNEL_DIR))


TARGET_LINUX_KERNEL_VERSION := 6.1

ifeq ($(TARGET_KERNEL_IMAGES_EXT),1)
    # ---------------------------------------------------------
    # РЕЖИМ 1: PREBUILT IMAGES (Готовые образы)
    # Используется, когда у нас есть готовые boot.img, vendor_dlkm.img и т.д.
    # Структура папки: prebuild-kernels/VariantName/*.img
    # ---------------------------------------------------------
    $(warning 🖼️ KERNEL MODE: PREBUILT IMAGES (Копирование готовых образов))
    BOARD_PREBUILT_BOOTIMAGE := $(TARGET_KERNEL_DIR)/boot.img
    TARGET_NO_KERNEL := false 
    BOARD_PREBUILT_INIT_BOOT_IMAGE := $(TARGET_KERNEL_DIR)/init_boot.img
    BOARD_PREBUILT_DTBOIMAGE := $(TARGET_KERNEL_DIR)/dtbo.img
    BOARD_PREBUILT_VENDOR_KERNEL_BOOTIMAGE := $(TARGET_KERNEL_DIR)/vendor_kernel_boot.img
    BOARD_USES_VENDOR_DLKM_IMAGE := true
    BOARD_PREBUILT_VENDOR_DLKMIMAGE := $(TARGET_KERNEL_DIR)/vendor_dlkm.img
    TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm
    BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := ext4
    BOARD_USES_SYSTEM_DLKM_IMAGE := true
    BOARD_PREBUILT_SYSTEM_DLKMIMAGE := $(TARGET_KERNEL_DIR)/system_dlkm.img
    TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm
    BOARD_SYSTEM_DLKMIMAGE_FILE_SYSTEM_TYPE := ext4

else
    # ---------------------------------------------------------
    # РЕЖИМ 2: COMPONENTS (Сборка из частей)
    # Используется, когда у нас есть Image.lz4 и россыпь модулей .ko
    # ---------------------------------------------------------
    $(warning 🧱 KERNEL MODE: COMPONENTS (Сборка boot.img из ядра и модулей))
    TARGET_PREBUILT_KERNEL := $(TARGET_KERNEL_DIR)/Image.lz4
    TARGET_NO_KERNEL := false
    BOARD_PREBUILT_DTBIMAGE_DIR := $(TARGET_KERNEL_DIR)
    BOARD_PREBUILT_DTBOIMAGE := $(TARGET_KERNEL_DIR)/dtbo.img
    TARGET_BOARD_KERNEL_HEADERS := $(TARGET_KERNEL_DIR)/kernel-headers
    BOARD_VENDOR_KERNEL_RAMDISK_KERNEL_MODULES_LOAD_FILE := $(strip $(shell cat $(TARGET_KERNEL_DIR)/vendor_kernel_boot.modules.load))
    BOARD_VENDOR_KERNEL_MODULES_LOAD := $(strip $(shell cat $(TARGET_KERNEL_DIR)/vendor_dlkm.modules.load))
    BOARD_SYSTEM_KERNEL_MODULES_LOAD := $(strip $(shell cat $(TARGET_KERNEL_DIR)/system_dlkm.modules.load))
    BOARD_SYSTEM_KERNEL_MODULES_BLOCKLIST_FILE := $(TARGET_KERNEL_DIR)/system_dlkm.modules.blocklist
    BOARD_VENDOR_KERNEL_MODULES_BLOCKLIST_FILE := $(TARGET_KERNEL_DIR)/vendor_dlkm.modules.blocklist
    BOARD_VENDOR_KERNEL_RAMDISK_KERNEL_MODULES := $(addprefix $(TARGET_KERNEL_DIR)/, $(notdir $(BOARD_VENDOR_KERNEL_RAMDISK_KERNEL_MODULES_LOAD_FILE)))
    ifneq ($(wildcard $(TARGET_KERNEL_DIR)/fips140.ko),)
        ifneq ($(filter fips140.ko,$(notdir $(BOARD_VENDOR_KERNEL_RAMDISK_KERNEL_MODULES_LOAD_FILE))),fips140.ko)
             BOARD_VENDOR_KERNEL_RAMDISK_KERNEL_MODULES += $(TARGET_KERNEL_DIR)/fips140.ko
        endif
    endif
    BOARD_VENDOR_KERNEL_MODULES := $(addprefix $(TARGET_KERNEL_DIR)/, $(notdir $(BOARD_VENDOR_KERNEL_MODULES_LOAD)))
    BOARD_SYSTEM_KERNEL_MODULES := $(addprefix $(TARGET_KERNEL_DIR)/, $(notdir $(BOARD_SYSTEM_KERNEL_MODULES_LOAD)))

endif