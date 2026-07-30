package com.encore.playlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SongVideoRepository extends JpaRepository<SongVideo, Long> {

    List<SongVideo> findByArtistMbidAndSongKeyIn(UUID artistMbid, Collection<String> songKeys);
}
