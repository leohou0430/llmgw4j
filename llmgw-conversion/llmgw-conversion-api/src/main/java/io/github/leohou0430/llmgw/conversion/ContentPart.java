package io.github.leohou0430.llmgw.conversion;

public sealed interface ContentPart permits TextPart, ImagePart, ToolCallPart, ToolResultPart, ThinkingPart {

}