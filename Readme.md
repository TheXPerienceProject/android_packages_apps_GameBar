===================================
GameBar 
original code from kenway214
====================================
sepolicy required if not included on your device tree

sepolicy/vendor/file_context
# Process and system statistics files
/proc/stat                                                                                       u:object_r:proc_stat:s0

sepolicy/private/gamebar.te
allow gameabar {
  activity_service
  activity_task_service
}

allow gamebar sysfs:dir r_dir_perms;
allow gamebar sysfs:file r_file_perms;

sepolicy/private/seapp_contexts
user=system seinfo=platform name=mx.xperience.gamebar domain=gamebar type=system_app_data_file levelFrom=all

sepolicy/public/gamebar.te
type gamebar, domain;
typeattribute gamebar mlstrustedsubject;

sepolicy/vendor/gamebar.te
allow gamebar vendor_sysfs_graphics:dir search;
allow gamebar vendor_sysfs_graphics:file rw_file_perms;
allow gamebar vendor_sysfs_kgsl:dir search;
allow gamebar vendor_sysfs_kgsl:{ file lnk_file } rw_file_perms;
allow gamebar vendor_sysfs_battery_supply:dir search;
allow gamebar vendor_sysfs_battery_supply:file r_file_perms;
allow gamebar proc_stat:file { read open getattr };
allow gamebar vendor_sysfs_kgsl_gpuclk:file { read open getattr };


or follow this commit

sepolicy
https://github.com/TheXPerienceProject/android_device_xperience_sepolicy/commit/b62d3c027dc8042d3e37329d2e841236d5b1efc3


original work at
https://github.com/project-dynamic/android_device_xiaomi_peridot/tree/401a25032ab8ceafe5df40e418326389fc24cb00
