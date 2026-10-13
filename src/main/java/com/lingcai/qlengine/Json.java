package com.lingcai.qlengine;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Jackson 单例封装。texts() 把非字符串元素序列化时要与 Python 的 json.dumps 同形。 */
public final class Json {

    public static final ObjectMapper M = new ObjectMapper();

    private Json() {
    }

    /** 对齐 Python {@code json.dumps(x, ensure_ascii=False)}。 */
    public static String dumps(Object x) {
        try {
            return M.writeValueAsString(x);
        } catch (Exception e) {
            return String.valueOf(x);
        }
    }
}
