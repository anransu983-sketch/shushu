// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0
package com.suanran.shushu.access;

/** Runs in a short-lived process Shizuku starts as the shell; used only to start the monitor. */
interface IShizukuShellService {
    // Shizuku's reserved transaction for ending a user service.
    void destroy() = 16777114;
    int getUid() = 1;
    /** Output of `sh -c command`, or a line starting "[SHIZUKU_" when it could not run to the end. */
    String execute(String command, long timeoutMs) = 2;
}
