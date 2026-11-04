package cn.copper.phase2.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 全项目共用的 JSON 编解码器。
 * <p>裸 {@code new ObjectMapper()} 不认识 {@code java.time} 类型：日期反序列化会直接失败，
 * 序列化又会写成数字数组。写入解析、留痕快照与幂等回放都必须用同一个实例，
 * 否则本地日期无法在请求与响应之间保持一致。</p>
 */
public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
}
