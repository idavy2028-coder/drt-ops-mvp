package com.idavy.drtops.domain.map;

import java.util.List;

public interface MapSearchProvider {

    List<AddressSuggestion> suggest(String keyword, String city);

    GeocodeResult geocode(String address, String city);
}
