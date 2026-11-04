package cn.copper.phase2.common;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    @RestController
    static class ProbeController {
        @GetMapping("/probe/invalid")
        Object invalid() {
            throw BusinessException.invalid("code", "编码不能为空");
        }

        @GetMapping("/probe/conflict")
        Object conflict() {
            throw BusinessException.versionConflict(1, 2);
        }

        @GetMapping("/probe/duplicate")
        Object duplicate() {
            throw BusinessException.duplicateCode("EQ-001");
        }

        @GetMapping("/probe/missing")
        Object missing() {
            throw BusinessException.notFound("设备不存在");
        }

        @GetMapping("/probe/typed")
        Object typed(@RequestParam int page) {
            return page;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void validationFailureReturns400WithFieldDetail() throws Exception {
        mvc.perform(get("/probe/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fields.code").value("编码不能为空"));
    }

    @Test
    void versionConflictReturns409WithBothVersions() throws Exception {
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.expectedVersion").value(1))
                .andExpect(jsonPath("$.data.currentVersion").value(2));
    }

    @Test
    void duplicateCodeReturns409() throws Exception {
        mvc.perform(get("/probe/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"))
                .andExpect(jsonPath("$.data.fields.code").value("该编码已存在"));
    }

    @Test
    void unknownObjectReturns404() throws Exception {
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void badParameterTypeReturns400InsteadOf500() throws Exception {
        mvc.perform(get("/probe/typed").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fields.page").value("取值类型不符合约定"));
    }
}
