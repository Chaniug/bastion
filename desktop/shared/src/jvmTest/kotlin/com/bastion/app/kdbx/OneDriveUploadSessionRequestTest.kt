package com.bastion.app.kdbx

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证 createUploadSession 请求体使用 conflictBehavior=replace。
 *
 * 回归场景（QA Round 2）：Json 未设置 encodeDefaults 时，值等于默认值的属性不序列化，
 * UploadSessionRequestDto() 会退化成 "{}"，导致 Graph 对 >4MB 同名文件按默认
 * conflictBehavior=rename 处理（生成 "file 1.kdbx" 副本），M2 同步正确性失效。
 */
class OneDriveUploadSessionRequestTest {

    @Test
    fun uploadSessionRequestBodyUsesReplaceConflictBehavior() {
        val source = OneDriveKeePassFileSource(authTokenProvider = { "" })
        val body = source.json.encodeToString(OneDriveKeePassFileSource.UploadSessionRequestDto())
        assertEquals("""{"item":{"@microsoft.graph.conflictBehavior":"replace"}}""", body)
    }
}
