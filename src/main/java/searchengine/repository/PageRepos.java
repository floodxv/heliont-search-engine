package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Page;
import searchengine.model.SiteModel;

import java.util.List;
import java.util.Optional;


@Repository
public interface PageRepos extends JpaRepository<Page, Long> {

    boolean existsByPathAndSiteModel(String path, SiteModel site);

    int countBySiteModel(SiteModel site);

    @Transactional
    @Modifying
    @Query("DELETE FROM Page p WHERE p.siteModel = :siteModel")
    void deleteBySiteModel(@Param("siteModel") SiteModel site);

    Optional<Page> findByPathAndSiteModel(String path, SiteModel site);

    @Query("SELECT i.page FROM Index i WHERE i.lemma.lemma = :lemma AND i.page.siteModel IN :sites")
    List<Page> findPagesByLemma(@Param("lemma") String lemma, @Param("sites") List<SiteModel> sites);
}