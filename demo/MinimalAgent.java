import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小的 ReAct Agent Loop，使用 DeepSeek API（OpenAI 兼容格式）。
 *
 * 运行方式：
 *   1. 设置环境变量：export DEEPSEEK_API_KEY=your_key
 *   2. 编译：javac demo/MinimalAgent.java
 *   3. 运行：java -cp demo MinimalAgent
 */
public class MinimalAgent {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";

    /**
     * Tool 接口：每个工具需要实现名称、描述和执行逻辑。
     */
    interface Tool {
        String name();
        String description();
        String execute(String input);
    }

    /**
     * Tool 注册中心：管理所有可用工具。
     */
    static class ToolRegistry {
        private final Map<String, Tool> tools = new HashMap<>();

        public void register(Tool tool) {
            tools.put(tool.name(), tool);
        }

        public Tool get(String name) {
            return tools.get(name);
        }

        public Collection<Tool> all() {
            return tools.values();
        }
    }

    /**
     * DeepSeek 客户端：封装 HTTP 调用。
     */
    static class DeepSeekClient {
        private final HttpClient client = HttpClient.newHttpClient();
        private final String apiKey;

        DeepSeekClient(String apiKey) {
            this.apiKey = apiKey;
        }

        String chat(String systemPrompt, String userPrompt) throws Exception {
            String json = String.format(
                "{\"model\":\"%s\",\"messages\":[" +
                "{\"role\":\"system\",\"content\":\"%s\"}," +
                "{\"role\":\"user\",\"content\":\"%s\"}" +
                "]}",
                MODEL, escapeJson(systemPrompt), escapeJson(userPrompt)
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return extractContent(response.body());
        }

        /**
         * 从 OpenAI 格式响应中提取最后一条 content。
         */
        private String extractContent(String responseBody) {
            Matcher matcher = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
                .matcher(responseBody);
            String last = "";
            while (matcher.find()) {
                last = unescapeJson(matcher.group(1));
            }
            return last;
        }

        private String escapeJson(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }

        private String unescapeJson(String s) {
            return s.replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
    }

    /**
     * Agent 循环：驱动 Thought → Action → Observation。
     */
    static class Agent {
        private final DeepSeekClient client;
        private final ToolRegistry registry;
        private final int maxIterations;

        Agent(DeepSeekClient client, ToolRegistry registry, int maxIterations) {
            this.client = client;
            this.registry = registry;
            this.maxIterations = maxIterations;
        }

        String run(String query) throws Exception {
            String systemPrompt = buildSystemPrompt();
            StringBuilder history = new StringBuilder();
            history.append("User: ").append(query).append("\n");

            for (int i = 0; i < maxIterations; i++) {
                String llmOutput = client.chat(systemPrompt, history.toString());
                System.out.println("--- LLM Response ---\n" + llmOutput + "\n");

                // 如果 LLM 直接给出最终答案，结束循环
                if (llmOutput.contains("Final Answer:")) {
                    return llmOutput.substring(llmOutput.indexOf("Final Answer:") + 13).trim();
                }

                String thought = extractLine(llmOutput, "Thought:");
                String action = extractLine(llmOutput, "Action:");
                String actionInput = extractLine(llmOutput, "Action Input:");

                System.out.println("Parsed Thought: " + thought);
                System.out.println("Parsed Action: " + action + "(" + actionInput + ")");

                // 执行工具
                Tool tool = registry.get(action);
                String observation;
                if (tool == null) {
                    observation = "Error: tool '" + action + "' not found.";
                } else {
                    observation = tool.execute(actionInput);
                }

                System.out.println("--- Observation ---\n" + observation + "\n");

                // 把这一轮结果追加到历史，继续循环
                history.append("Assistant: ").append(llmOutput).append("\n");
                history.append("Observation: ").append(observation).append("\n");
            }

            return "Reached max iterations without final answer.";
        }

        private String buildSystemPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("You are a helpful assistant that solves problems by using tools.\n");
            sb.append("Think step by step. For each step, output exactly in this format:\n\n");
            sb.append("Thought: [your reasoning about what to do next]\n");
            sb.append("Action: [tool name]\n");
            sb.append("Action Input: [input for the tool]\n\n");
            sb.append("When you have enough information to answer the user, output:\n");
            sb.append("Final Answer: [your final answer]\n\n");
            sb.append("Available tools:\n");
            for (Tool tool : registry.all()) {
                sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
            }
            return sb.toString();
        }

        private String extractLine(String text, String prefix) {
            int start = text.indexOf(prefix);
            if (start < 0) {
                return "";
            }
            start += prefix.length();
            int end = text.indexOf("\n", start);
            if (end < 0) {
                end = text.length();
            }
            return text.substring(start, end).trim();
        }
    }

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set the DEEPSEEK_API_KEY environment variable.");
            System.exit(1);
        }

        // 注册工具
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "search"; }
            public String description() { return "Search for emergency safety documents by keyword."; }
            public String execute(String input) {
                // 这里可以替换成真实检索逻辑（Qdrant / ES）
                return "Found documents about: " + input;
            }
        });
        registry.register(new Tool() {
            public String name() { return "calculator"; }
            public String description() { return "Calculate a simple math expression, e.g. 12 * 8."; }
            public String execute(String input) {
                // 仅作为示例，真实场景可用脚本引擎或解析器
                return "Result of " + input + " is calculated.";
            }
        });

        DeepSeekClient client = new DeepSeekClient(apiKey);
        Agent agent = new Agent(client, registry, 5);

        String query = "What is the emergency procedure for chemical spill?";
        String answer = agent.run(query);

        System.out.println("\n=== Final Answer ===\n" + answer);
    }
}
