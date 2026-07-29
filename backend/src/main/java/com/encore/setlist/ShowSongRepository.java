package com.encore.setlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShowSongRepository extends JpaRepository<ShowSong, Long> {

    List<ShowSong> findByShow_SetlistId(String setlistId);
}
