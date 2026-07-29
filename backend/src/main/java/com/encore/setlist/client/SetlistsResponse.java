package com.encore.setlist.client;

import java.util.List;

/** GET /1.0/artist/{mbid}/setlists 응답. 최근 공연부터 내려온다. */
public record SetlistsResponse(
        Integer total,
        Integer itemsPerPage,
        Integer page,
        List<SetlistDto> setlist
) {

    public SetlistsResponse {
        setlist = setlist != null ? List.copyOf(setlist) : List.of();
    }
}
