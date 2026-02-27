package pl.mindrush.backend.quiz;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=true"
})
@AutoConfigureMockMvc
class QuizControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listQuizzes_returnsSeededQuiz() throws Exception {
        mockMvc.perform(get("/api/quizzes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].title").isString())
                .andExpect(jsonPath("$[0].source").value("official"))
                .andExpect(jsonPath("$[0].questionCount").isNumber());
    }

    @Test
    void getQuizQuestions_doesNotExposeCorrectAnswers() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/quizzes").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String body = list.getResponse().getContentAsString();
        Long quizId = firstNumberAfter(body, "\"id\":").orElseThrow();

        MvcResult questions = mockMvc.perform(get("/api/quizzes/" + quizId + "/questions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].prompt").isString())
                .andExpect(jsonPath("$[0].options[0].id").isNumber())
                .andExpect(jsonPath("$[0].options[0].text").isString())
                .andReturn();

        String questionsBody = questions.getResponse().getContentAsString();
        assertThat(questionsBody).doesNotContain("\"correct\"");
    }

    private static java.util.Optional<Long> firstNumberAfter(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx < 0) return java.util.Optional.empty();
        int start = idx + marker.length();
        int end = start;
        while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
        int numStart = end;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        if (numStart == end) return java.util.Optional.empty();
        return java.util.Optional.of(Long.parseLong(text.substring(numStart, end)));
    }
}
