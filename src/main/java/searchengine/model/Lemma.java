package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@Data
public class Lemma {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String lemma;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private SiteModel siteModel;

    @Column(nullable = false)
    private int frequency;

    @OneToMany(mappedBy = "lemma")  // Здесь mappedBy указывает на поле 'lemma' в Index
    private List<Index> indexes = new ArrayList<>();

    public Lemma() {}
}