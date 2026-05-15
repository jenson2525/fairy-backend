package cn.nuaa.jensonxu.fairy.integration.service.tools.service.impl;

import cn.nuaa.jensonxu.fairy.integration.service.tools.service.McpToolService;
import cn.nuaa.jensonxu.fairy.integration.service.tools.utils.ZoneUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TimeService implements McpToolService {

    @Tool(description = "Get the time of a specified city.")
    public String getTimeByTimeZone(@ToolParam(description = "Time zone id, such as Asia/Shanghai") String timeZoneId) {
        log.info("[time service] The current time zone is {}", timeZoneId);
        return String.format("The current time zone is %s and the current time is " + "%s", timeZoneId,
                ZoneUtils.getTimeByZoneId(timeZoneId));
    }
}
