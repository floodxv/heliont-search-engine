package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.SiteModel;
import searchengine.model.Status;

import java.util.List;
import java.util.Optional;

@Repository
public interface SiteRepos extends JpaRepository<SiteModel, Long> {

    @Query("SELECT s FROM SiteModel s WHERE s.url = :url")
    SiteModel findByUrl(@Param("url") String url);

    List<SiteModel> findAllByStatus(Status status);

    @Query("SELECT s FROM SiteModel s WHERE s.url = :url")
    Optional<SiteModel> findOptionalByUrl(@Param("url") String url);
}