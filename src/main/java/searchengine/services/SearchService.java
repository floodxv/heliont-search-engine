package searchengine.services;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.lemmatizer.Lemmatizer;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.SiteModel;
import searchengine.model.Status;
import searchengine.repository.LemmaRepos;
import searchengine.repository.PageRepos;
import searchengine.repository.SearchIndexRepos;
import searchengine.repository.SiteRepos;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
public class SearchService {

    private final PageRepos pageRepos;
    private final LemmaRepos lemmaRepos;
    private final SearchIndexRepos searchIndexRepos;
    private final SiteRepos siteRepos;

    public void validateQuery(String query){
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Поисковый запрос не может быть пустым");
        }

        if (query.length() > 100) {
            throw new IllegalArgumentException("Поисковый запрос слишком длинный");
        }

        if (!query.matches("[a-zA-Zа-яА-Я0-9\\s]+")) {
            throw new IllegalArgumentException("Поисковый запрос содержит недопустимые символы");
        }

    }
    public List<SiteModel>getSitesToSearch(String siteUrl) {
        List<SiteModel> sitesToSearch;
        if (siteUrl == null || siteUrl.isBlank()) {
            sitesToSearch = siteRepos.findAll().stream()
                    .filter(site -> site.getStatus() == Status.INDEXED)
                    .collect(Collectors.toList());
        } else {
            SiteModel site = siteRepos.findOptionalByUrl(siteUrl)
                    .orElseThrow(() -> new IllegalArgumentException("Сайт не найден или не проиндексирован"));
            if (site.getStatus() != Status.INDEXED) {
                throw new IllegalStateException("Сайт ещё не проиндексирован");
            }
            sitesToSearch = List.of(site);
        }
        return sitesToSearch;
    }

    public List<SiteModel> validateSitesIndexed(List<SiteModel> sites){
        if (sites.isEmpty()) {
            throw new IllegalStateException("Нет проиндексированных сайтов для поиска");
        }
        return sites;
    }

    public List<String> extractQueryLemmas(String query, List<SiteModel> sitesToSearch) {
        Map<String, Integer> queryLemmas = Lemmatizer.getLemmaCounts(query);
        if (queryLemmas.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> filteredLemmas = new ArrayList<>(queryLemmas.keySet());
        filteredLemmas.sort(Comparator.comparingLong(
                lemma -> lemmaRepos.countPagesByLemma(lemma, sitesToSearch))
        );
        return filteredLemmas;
    }

    public List<Page> findCandidatePages(List<String> lemmas, List<SiteModel> sitesToSearch){
        List<Page> candidatePages = pageRepos.findPagesByLemma(lemmas.get(0), sitesToSearch);

        for (int i = 1; i < lemmas.size(); i++) {
            String lemmaStr = lemmas.get(i);
            candidatePages = candidatePages.stream()
                    .filter(page -> {
                        Lemma lemma = lemmaRepos.findByLemmaAndSiteModel(lemmaStr, page.getSiteModel()).orElse(null);
                        return lemma != null && searchIndexRepos.existsByPageAndLemma(page, lemma);
                    })
                    .collect(Collectors.toList());
            if (candidatePages.isEmpty()) break;
        }
        return candidatePages;
    }

    public Map<Page, Double> calculateAbsoluteRelevance(List<Page> pages, List<String> lemmas){
        Map<Page, Double> absRelevanceMap = new HashMap<>();
        for (Page page : pages) {
            double absRelevance = 0;
            for (String lemmaStr : lemmas) {
                Lemma lemma = lemmaRepos.findByLemmaAndSiteModel(lemmaStr, page.getSiteModel()).orElse(null);
                if (lemma != null) {
                    Double rank = searchIndexRepos.findRankByPageAndLemma(page, lemma);
                    if (rank != null) {
                        absRelevance += rank;
                    }
                }
            }
            absRelevanceMap.put(page, absRelevance);
        }
        return absRelevanceMap;
    }

    public List<SearchResultItem> buildSearchResults(
            List<Page> pages,
            Map<Page, Double> absRelevanceMap,
            List<String> lemmas,
            int offset,
            int limit,
            String query
    ) {
        double maxAbsRelevance = absRelevanceMap.values().stream()
                .max(Double::compare)
                .orElse(1.0);

        return pages.stream()
                .map(page -> {
                    SiteModel site = page.getSiteModel();
                    double relRelevance = absRelevanceMap.get(page) / maxAbsRelevance;
                    String snippet = buildSnippet(page.getContent(), query);
                    String title = extractTitle(page.getContent());

                    return new SearchResultItem(
                            site.getUrl(),
                            site.getName(),
                            page.getPath(),
                            title,
                            snippet,
                            relRelevance
                    );
                })
                .sorted(Comparator.comparingDouble(SearchResultItem::getRelevance).reversed())
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        try {
            validateQuery(query);
            List<SiteModel> sites = validateSitesIndexed(getSitesToSearch(siteUrl));
            List<String> lemmas = extractQueryLemmas(query, sites);

            if (lemmas.isEmpty()) {
                return new SearchResponse(false, 0, Collections.emptyList());
            }

            List<Page> candidatePages = findCandidatePages(lemmas, sites);
            if (candidatePages.isEmpty()) {
                return new SearchResponse(true, 0, Collections.emptyList());
            }

            Map<Page, Double> absRelevanceMap = calculateAbsoluteRelevance(candidatePages, lemmas);
            List<SearchResultItem> results = buildSearchResults(candidatePages, absRelevanceMap, lemmas, offset, limit, query);

            return new SearchResponse(true, candidatePages.size(), results);
        } catch (Exception e) {
            return new SearchResponse(false, 0, Collections.emptyList());
        }
    }

    private String extractTitle(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            String title = doc.title();
            return title != null && !title.isBlank() ? title : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String buildSnippet(String content, String query) {
        String text = Jsoup.parse(content).text();

        Map<String, Integer> queryLemmas = Lemmatizer.getLemmaCounts(query);
        if (queryLemmas.isEmpty()) return "";

        int snippetLength = 150;
        int pos = -1;
        String lowerText = text.toLowerCase();

        for (String lemma : queryLemmas.keySet()) {
            int idx = lowerText.indexOf(lemma.toLowerCase());
            if (idx >= 0 && (pos == -1 || idx < pos)) {
                pos = idx;
            }
        }

        if (pos == -1) {
            return text.length() > snippetLength ? text.substring(0, snippetLength) + "..." : text;
        }

        int start = Math.max(0, pos - snippetLength / 3);
        int end = Math.min(text.length(), pos + snippetLength * 2 / 3);
        String snippet = text.substring(start, end);

        for (String lemma : queryLemmas.keySet()) {
            snippet = snippet.replaceAll("(?i)" + Pattern.quote(lemma), "<b>" + lemma + "</b>");
        }

        return "..." + snippet + "...";
    }

    @Data
    @AllArgsConstructor
    public static class SearchResultItem {
        private String site;
        private String siteName;
        private String uri;
        private String title;
        private String snippet;
        private double relevance;
    }

    @Data
    @AllArgsConstructor
    public static class SearchResponse {
        private boolean result;
        private int count;
        private List<SearchResultItem> data;
    }
}