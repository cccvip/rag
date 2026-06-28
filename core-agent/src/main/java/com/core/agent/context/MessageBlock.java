package com.core.agent.context;

/**
 * 上下文中的一个内容块。
 *
 * 把 Prompt 内容拆分为不同类型的块，便于 ContextManager 按策略裁剪。
 */
public class MessageBlock {

    private final BlockType type;
    private final String content;
    private final int tokenCount;

    public MessageBlock(BlockType type, String content, int tokenCount) {
        this.type = type;
        this.content = content;
        this.tokenCount = tokenCount;
    }

    public static MessageBlock systemPrompt(String content, int tokenCount) {
        return new MessageBlock(BlockType.SYSTEM_PROMPT, content, tokenCount);
    }

    public static MessageBlock userQuestion(String content, int tokenCount) {
        return new MessageBlock(BlockType.USER_QUESTION, content, tokenCount);
    }

    public static MessageBlock assistantAnswer(String content, int tokenCount) {
        return new MessageBlock(BlockType.ASSISTANT_ANSWER, content, tokenCount);
    }

    public static MessageBlock toolResult(String content, int tokenCount) {
        return new MessageBlock(BlockType.TOOL_RESULT, content, tokenCount);
    }

    public static MessageBlock thought(String content, int tokenCount) {
        return new MessageBlock(BlockType.THOUGHT, content, tokenCount);
    }

    public static MessageBlock systemMeta(String content, int tokenCount) {
        return new MessageBlock(BlockType.SYSTEM_META, content, tokenCount);
    }

    public BlockType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    @Override
    public String toString() {
        return "MessageBlock{type=" + type + ", tokenCount=" + tokenCount + "}";
    }
}
