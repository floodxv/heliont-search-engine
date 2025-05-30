package searchengine.lemmatizer;

import org.apache.lucene.morphology.russian.RussianLuceneMorphology;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lemmatizer {

    private static RussianLuceneMorphology luceneMorph;

    static {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException("Ошибка инициализации RussianLuceneMorphology", e);
        }
    }

    public static Map<String, Integer> getLemmaCounts(String text) {
        Map<String, Integer> lemmaCounts = new HashMap<>();

        String[] words = text.toLowerCase().split("[^а-яё]+");

        for (String word : words) {
            if (word.isEmpty() || !luceneMorph.checkString(word)) {
                continue;
            }

            try {
                List<String> morphInfos = luceneMorph.getMorphInfo(word);

                boolean isServicePart = morphInfos.stream().anyMatch(info ->
                        info.contains("МЕЖД") || info.contains("СОЮЗ") ||
                                info.contains("ПРЕДЛ") || info.contains("ЧАСТ"));
                if (isServicePart) continue;

                List<String> normalForms = luceneMorph.getNormalForms(word);
                if (!normalForms.isEmpty()) {
                    String lemma = normalForms.get(0);
                    if (!lemma.isEmpty()) {
                        lemmaCounts.put(lemma, lemmaCounts.getOrDefault(lemma, 0) + 1);
                    }
                }
            } catch (Exception e) {
                System.err.println("Ошибка при лемматизации слова: " + word + " -> " + e.getMessage());
            }
        }

        return lemmaCounts;
    }
}