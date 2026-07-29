package com.encore.api.admin;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.setlist.client.SetlistFmClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 관리자 — 수집 대상 아티스트 검색·등록. */
@RestController
@RequestMapping("/api/admin/artists")
public class AdminArtistController {

    private final ArtistRepository artistRepository;
    private final SetlistFmClient setlistFmClient;

    public AdminArtistController(ArtistRepository artistRepository, SetlistFmClient setlistFmClient) {
        this.artistRepository = artistRepository;
        this.setlistFmClient = setlistFmClient;
    }

    /** 등록된 아티스트 목록 — 이벤트 등록 폼의 선택지. */
    @GetMapping
    public List<RegisteredArtist> registeredArtists() {
        return artistRepository.findAll().stream()
                .map(artist -> new RegisteredArtist(artist.getMbid(), artist.getName(), artist.isTarget()))
                .toList();
    }

    /**
     * setlist.fm 검색 — MBID 확보용. 본체가 첫 번째라는 보장이 없으므로(실측)
     * disambiguation을 함께 보여주고 관리자가 후보를 고른다.
     */
    @GetMapping("/search")
    public List<ArtistCandidate> search(@RequestParam String name) {
        return setlistFmClient.searchArtists(name).artist().stream()
                .map(dto -> new ArtistCandidate(
                        dto.mbid(), dto.name(), dto.sortName(), dto.disambiguation(), dto.url(),
                        dto.mbid() != null && artistRepository.existsById(UUID.fromString(dto.mbid()))))
                .toList();
    }

    /** 후보를 수집 대상으로 등록한다. 이미 있으면 표기 정보를 갱신하고 대상으로만 되돌린다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RegisteredArtist register(@Valid @RequestBody RegisterArtistRequest request) {
        Artist artist = artistRepository.findById(request.mbid()).orElse(null);
        if (artist == null) {
            artist = artistRepository.save(Artist.builder()
                    .mbid(request.mbid())
                    .name(request.name())
                    .sortName(request.sortName())
                    .setlistFmUrl(request.setlistFmUrl())
                    .target(true)
                    .build());
        } else {
            artist.updateProfile(request.name(), request.sortName(), request.setlistFmUrl());
            artist.markAsCollectionTarget();
        }
        return new RegisteredArtist(artist.getMbid(), artist.getName(), artist.isTarget());
    }

    public record ArtistCandidate(String mbid, String name, String sortName, String disambiguation,
                                  String url, boolean alreadyRegistered) {
    }

    public record RegisterArtistRequest(
            @NotNull UUID mbid,
            @NotBlank String name,
            String sortName,
            String setlistFmUrl) {
    }

    public record RegisteredArtist(UUID mbid, String name, boolean target) {
    }
}
