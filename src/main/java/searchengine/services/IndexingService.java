package searchengine.services;

public interface IndexingService {
    boolean startIndexing();
    boolean stopIndexing();
    boolean isIndexing();
    boolean indexPage(String url);
}
