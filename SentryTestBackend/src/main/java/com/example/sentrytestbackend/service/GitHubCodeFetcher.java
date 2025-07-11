import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;

/**
 * Service for fetching code snippets from GitHub using the GitHub API.
 * Given a file path and line number, returns a snippet of code around that line.
 */
@Service
public class GitHubCodeFetcher {
    @Value("${github.api.token}")
    private String githubApiToken;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Fetches a code snippet from a GitHub file around a specific line number.
     * @param owner GitHub repository owner
     * @param repo GitHub repository name
     * @param branch Branch name
     * @param filePath Path to the file in the repository
     * @param lineNumber Line number to center the snippet around
     * @return Code snippet as a string, or error message if fetch fails
     */
    public String fetchCodeSnippet(String owner, String repo, String branch, String filePath, int lineNumber) {
        try {
            String url = String.format(
                "https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                owner, repo, filePath, branch
            );
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/vnd.github.v3.raw");
            headers.set("Authorization", "token " + githubApiToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());
            String encodedContent = root.path("content").asText();
            String decodedContent = new String(Base64.getDecoder().decode(encodedContent));
            String[] lines = decodedContent.split("\n");

            int start = Math.max(0, lineNumber - 11);
            int end = Math.min(lines.length, lineNumber + 10);

            StringBuilder snippet = new StringBuilder();
            snippet.append("File: ").append(filePath).append(" (lines ").append(start + 1).append("-").append(end).append(")\n");
            for (int i = start; i < end; i++) {
                snippet.append((i + 1)).append(": ").append(lines[i]).append("\n");
            }
            return snippet.toString();
        } catch (Exception e) {
            return "Failed to fetch code snippet: " + e.getMessage();
        }
    }
}
