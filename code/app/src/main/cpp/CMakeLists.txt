# SPDX-FileCopyrightText: 2026 ybblab
# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.22.1)
project(jiumi_shell LANGUAGES C)

add_executable(jiumi_shell shell_launcher.c)
# PackageManager extracts executable files from the APK's lib/<abi>/ entries.
set_target_properties(jiumi_shell PROPERTIES OUTPUT_NAME libjiumi_shell.so)
target_compile_options(jiumi_shell PRIVATE -Wall -Wextra -Werror)
