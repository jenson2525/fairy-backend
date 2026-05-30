package cn.nuaa.jensonxu.fairy.integration.service.tools.service.impl;

import cn.nuaa.jensonxu.fairy.common.file.FileProcessResult;
import cn.nuaa.jensonxu.fairy.common.file.FileProcessor;
import cn.nuaa.jensonxu.fairy.common.repository.minio.MinioProperties;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRisk;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRiskLevel;
import cn.nuaa.jensonxu.fairy.integration.service.tools.service.McpToolService;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 文件读取工具
 * 从 MinIO 下载用户上传的文件，提取其文本内容后返回给 Agent 模型进行分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ToolRisk(ToolRiskLevel.READ_ONLY)
public class FileReadService implements McpToolService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /**
     * 读取并提取用户上传文件的文本内容
     */
    @Tool(description = """
            Read and extract the text content from an uploaded file stored in the system.
            Use this tool when the user has uploaded a file and wants you to read, analyze,
            summarize, or answer questions about its content.
            Supports PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx),
            plain text (.txt), Markdown (.md), and more.
            Images are not supported at this time.
            Returns the extracted plain text content of the file.
            """)
    public String readUploadedFile(
            @ToolParam(description = "The MinIO storage path of the file, provided in the conversation context")
            String minioPath,
            @ToolParam(description = "The original file name including extension, e.g. 'report.pdf', 'data.xlsx'")
            String fileName) {
        log.info("[file-read] 开始读取文件: {}, 路径: {}", fileName, minioPath);
        try {
            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(minioPath)
                            .build()
            );
            FileProcessResult result = FileProcessor.process(inputStream, fileName);
            if (!result.isSuccess()) {
                log.warn("[file-read] 文件解析失败: {}, 原因: {}", fileName, result.getErrorMessage());
                return "File reading failed: " + result.getErrorMessage();
            }

            String content = result.getTextContent();
            log.info("[file-read] 文件解析成功: {}, 内容长度: {} 字符", fileName, content.length());
            return String.format("Content of file [%s]:\n\n%s", fileName, content);
        } catch (Exception e) {
            log.error("[file-read] 文件读取异常: {}, 路径: {}", fileName, minioPath, e);
            return "File reading failed: " + e.getMessage();
        }
    }
}