package org.example.back.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.example.back.common.exception.GlobalExceptionHandler;
import org.example.back.config.SaTokenConfig;
import org.example.back.config.StpInterfaceImpl;
import org.example.back.mapper.SysErrorLogMapper;
import org.example.back.service.AnnualStatsService;
import org.example.back.vo.AnnualStatsVO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AnnualStatsAuthTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
class AnnualStatsAuthTest {

    private static final String DEVICE = "PC";
    private static final AtomicLong TEST_USER_ID_SEQUENCE = new AtomicLong(21000L);

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @Import({
            AnnualStatsController.class,
            SaTokenConfig.class,
            StpInterfaceImpl.class,
            GlobalExceptionHandler.class
    })
    static class TestApp {
    }

    @MockBean
    private AnnualStatsService annualStatsService;

    @MockBean
    private SysErrorLogMapper sysErrorLogMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    private String loginWithRole(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        try {
            long userId = TEST_USER_ID_SEQUENCE.incrementAndGet();
            StpUtil.login(userId, DEVICE);
            String token = StpUtil.getTokenValueByLoginId(userId, DEVICE);
            // 直接写入账号 Session：供 StpInterfaceImpl 读取角色列表
            StpUtil.getSessionByLoginId(userId).set("role", role);
            return token;
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void AnnualStatsApi_shouldAllowAdmin() throws Exception {
        when(annualStatsService.getAnnualStats()).thenReturn(List.of(new AnnualStatsVO()));

        String token = loginWithRole("admin");

        mockMvc.perform(get("/business/annual-stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void AnnualStatsApi_shouldRejectSuperAdmin() throws Exception {
        String token = loginWithRole("superadmin");

        mockMvc.perform(get("/business/annual-stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void AnnualStatsApi_shouldRejectEmployee() throws Exception {
        String token = loginWithRole("employee");

        mockMvc.perform(get("/business/annual-stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void AnnualStatsExportApi_shouldAllowAdminAndReturnXlsx() throws Exception {
        when(annualStatsService.exportAnnualStats()).thenReturn(new byte[]{1, 2, 3});

        String token = loginWithRole("admin");

        mockMvc.perform(get("/business/annual-stats/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(header().exists("Content-Disposition"));
    }

    @Test
    void AnnualStatsExportApi_shouldRejectSuperAdmin() throws Exception {
        String token = loginWithRole("superadmin");

        mockMvc.perform(get("/business/annual-stats/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void AnnualStatsExportApi_shouldRejectEmployee() throws Exception {
        String token = loginWithRole("employee");

        mockMvc.perform(get("/business/annual-stats/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
