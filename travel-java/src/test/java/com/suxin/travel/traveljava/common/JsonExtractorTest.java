package com.suxin.travel.traveljava.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** JsonExtractor 单元测试：三级兜底策略各路径都要命中 */
class JsonExtractorTest {

    @Test
    @DisplayName("```json 代码块优先")
    void jsonCodeBlock() {
        String resp = "好的，以下是您的行程：\n```json\n{\"city\":\"北京\"}\n```\n祝您旅途愉快";
        assertEquals("{\"city\":\"北京\"}", JsonExtractor.extract(resp));
    }

    @Test
    @DisplayName("无语言标记的 ``` 代码块次之")
    void plainCodeBlock() {
        String resp = "```\n{\"city\":\"上海\"}\n```";
        assertEquals("{\"city\":\"上海\"}", JsonExtractor.extract(resp));
    }

    @Test
    @DisplayName("无代码块时兜底截取首尾大括号")
    void braceFallback() {
        String resp = "行程如下：{\"city\":\"广州\"} 希望你喜欢";
        assertEquals("{\"city\":\"广州\"}", JsonExtractor.extract(resp));
    }

    @Test
    @DisplayName("嵌套 JSON 取最外层")
    void nestedJson() {
        String resp = "{\"a\":{\"b\":1}}";
        assertEquals(resp, JsonExtractor.extract(resp));
    }

    @Test
    @DisplayName("空与无 JSON 输入返回 null")
    void nullAndMissing() {
        assertNull(JsonExtractor.extract(null));
        assertNull(JsonExtractor.extract(""));
        assertNull(JsonExtractor.extract("抱歉，我无法生成行程。"));
    }
}
