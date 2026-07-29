package com.encore.setlist.client;

import java.util.List;

/** GET /1.0/search/artists 응답. total / itemsPerPage / page로 페이징한다. */
public record ArtistSearchResponse(
        Integer total,
        Integer itemsPerPage,
        Integer page,
        List<ArtistDto> artist
) {

    public ArtistSearchResponse {
        artist = artist != null ? List.copyOf(artist) : List.of();
    }
}
