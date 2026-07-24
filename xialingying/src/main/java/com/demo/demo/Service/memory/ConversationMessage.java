package com.demo.demo.Service.memory;

/**
 * 一条对话消息：角色 + 内容。
 * 角色为 "user" 或 "assistant"，System Prompt 不存储。
 */
public record ConversationMessage(String role, String content) {
}
