// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

#include <grp.h>
#include <stdbool.h>
#include <stdio.h>
#include <sys/capability.h>
#include <sys/prctl.h>
#include <unistd.h>

enum { SHELL_ID = 2000, INET_GROUP = 3003 };
// The Android ADB shell's supplementary groups, including inet for network access.
static const gid_t shell_groups[] = {
    1004, 1007, 1011, 1015, 1028, 1078, 1079, 3001, 3002, INET_GROUP, 3006, 3009, 3011, 3012,
};
enum { SHELL_GROUP_COUNT = sizeof(shell_groups) / sizeof(shell_groups[0]) };

static bool has_shell_groups(void) {
    // A profile already running as shell may include the primary group or omit optional groups.
    gid_t groups[SHELL_GROUP_COUNT + 1];
    int count = getgroups(SHELL_GROUP_COUNT + 1, groups);
    bool has_inet = false;
    for (int i = 0; i < count; i++) {
        if (groups[i] == SHELL_ID) continue;
        size_t j = 0;
        while (j < SHELL_GROUP_COUNT && groups[i] != shell_groups[j]) j++;
        if (j == SHELL_GROUP_COUNT) return false;
        if (groups[i] == INET_GROUP) has_inet = true;
    }
    return has_inet;
}

static bool has_shell_ids(void) {
    uid_t real_uid, effective_uid, saved_uid;
    gid_t real_gid, effective_gid, saved_gid;
    return getresuid(&real_uid, &effective_uid, &saved_uid) == 0 &&
           getresgid(&real_gid, &effective_gid, &saved_gid) == 0 &&
           real_uid == SHELL_ID && effective_uid == SHELL_ID && saved_uid == SHELL_ID &&
           real_gid == SHELL_ID && effective_gid == SHELL_ID && saved_gid == SHELL_ID;
}

int main(int argc, char **argv) {
    if (argc != 2) return 1;
    if (geteuid() == 0 &&
        (setgroups(SHELL_GROUP_COUNT, shell_groups) != 0 ||
         setresgid(SHELL_ID, SHELL_ID, SHELL_ID) != 0 ||
         setresuid(SHELL_ID, SHELL_ID, SHELL_ID) != 0)) {
        perror("jiumi shell: switch identity");
        return 1;
    }
    if (!has_shell_ids() || !has_shell_groups()) {
        fputs("jiumi shell: unexpected user or groups\n", stderr);
        return 1;
    }

    // Clear permitted, effective and inheritable capabilities. Clearing the permitted and inheritable
    // sets also clears ambient capabilities. Prevent future execs from granting new privileges.
    struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct empty_caps[_LINUX_CAPABILITY_U32S_3] = {0};
    if (capset(&header, empty_caps) != 0 || prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) != 0) {
        perror("jiumi shell: drop privileges");
        return 1;
    }
    execl("/system/bin/sh", "sh", "-c", argv[1], (char *) NULL);
    perror("jiumi shell: start shell");
    return 1;
}
