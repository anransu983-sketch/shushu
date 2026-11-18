// SPDX-FileCopyrightText: 2026 ybblab
// SPDX-License-Identifier: Apache-2.0

package com.suanran.shushu

import org.json.JSONObject

data class Footprint(val pssKb: Long, val privateDirtyKb: Long, val cpuMs: Long, val uptimeMs: Long)

object ProbeJson {
    fun footprint(json: String): Footprint {
        val root = JSONObject(json)
        return Footprint(root.optLong("pssKb"), root.optLong("privateDirtyKb"), root.optLong("cpuMs"), root.optLong("uptimeMs"))
    }
}
