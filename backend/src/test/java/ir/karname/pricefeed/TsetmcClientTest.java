package ir.karname.pricefeed;

import ir.karname.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TsetmcClientTest {

    private static final String ID = "35425587644337450";
    private final PriceHttpClient http = mock(PriceHttpClient.class);
    private final TsetmcClient client = new TsetmcClient(http,
            Clock.fixed(Instant.parse("2026-10-06T08:30:00Z"), ZoneOffset.UTC), "https://cdn.tsetmc.com/api");

    @Test
    void searchKeepsExactIdentifiersNormalizesPersianAndOmitsInactiveAndDerivativeResults() {
        answer("""
                {"instrumentSearch":[
                  {"insCode":"35425587644337450","lVal18AFC":"فملي","lVal30":"ملي صنايع مس ايران","lastDate":1,"flow":1},
                  {"insCode":"35425587644337450","lVal18AFC":"فملی","lVal30":"تکراری","lastDate":1},
                  {"insCode":"123","lVal18AFC":"قدیمی","lVal30":"قدیمی","lastDate":0},
                  {"insCode":"456","lVal18AFC":"ضملی","lVal30":"اختیار","lastDate":1,"flow":3},
                  {"insCode":"bad","lVal18AFC":"خراب","lVal30":"خراب"}]}
                """);
        assertThat(client.search("  فملي /?  ")).containsExactly(new TsetmcClient.StockInstrument(ID, "فملی", "ملی صنایع مس ایران"));
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(http).get(uri.capture(), any(), any());
        assertThat(uri.getValue().toString()).endsWith("/%D9%81%D9%85%D9%84%DB%8C%20%2F%3F");
        assertThat(uri.getValue().getQuery()).isNull();
    }

    @Test
    void blankSearchDoesNotCallRemoteAndInvalidEnvelopeIsAnError() {
        assertThat(client.search(" ")).isEmpty();
        verifyNoInteractions(http);
        answer("{\"message\":\"maintenance\"}");
        assertThatThrownBy(() -> client.search("فملی")).isInstanceOf(PriceFetchException.class);
    }

    @Test
    void metadataMustReferToTheRequestedInstrument() {
        answer("""
                {"instrumentInfo":{"insCode":"35425587644337450","lVal18AFC":"فملي","lVal30":"ملي صنايع مس ايران","flow":1}}
                """);
        assertThat(client.instrument(ID).symbol()).isEqualTo("فملی");
        assertThatThrownBy(() -> client.instrument("12345")).isInstanceOf(PriceFetchException.class);
    }

    @Test
    void indexMetadataCannotBeAddedAsAShare() {
        answer("""
                {"instrumentInfo":{"insCode":"35425587644337450","lVal18AFC":"شاخص کل","lVal30":"شاخص کل بورس","flow":1,"yVal":"68"}}
                """);
        assertThatThrownBy(() -> client.instrument(ID)).isInstanceOf(PriceFetchException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "01", "-1", "123/456", "../1", "123?x=1", "۱۲۳", "123456789012345678901"})
    void malformedIdentifiersAreRejectedBeforeNetwork(String id) {
        assertThatThrownBy(() -> client.instrument(id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.quote(id)).isInstanceOf(ApiException.class);
        verifyNoInteractions(http);
    }

    @Test
    void genuineQuoteUsesClosingPriceInTomanAndItsHistoricalTehranTimestamp() {
        // Excerpt of 5j9/tsetmc tests/testdata/closing_price_info_karis.json (GetClosingPriceInfo).
        // The official endpoint uses "0" as a legitimate insCode sentinel.
        answer("""
                {"closingPriceInfo":{"insCode":"0","dEven":20251008,"hEven":122952,
                  "lastHEven":122952,"finalLastDate":20251008,"pClosing":35358.0,"pDrCotVal":35540.0,"qTotTran5J":123}}
                """);
        TsetmcClient.StockQuote quote = client.quote(ID);
        assertThat(quote.priceToman()).isEqualByComparingTo("3535.8");
        assertThat(quote.quotedAt()).isEqualTo(Instant.parse("2025-10-08T08:59:52Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"123\"", "null", "35425587644337451", "\"\""})
    void anotherInstrumentOrMissingIdentityCannotSupplyThePrice(String returnedId) {
        answer(quote(returnedId, "20261006", "110000", "30000"));
        assertThatThrownBy(() -> client.quote(ID)).isInstanceOf(PriceFetchException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "null", "\"n/a\"", "1e30"})
    void invalidClosingPriceNeverFallsBackToLastTrade(String price) {
        answer(quote("\"" + ID + "\"", "20261006", "110000", price));
        assertThatThrownBy(() -> client.quote(ID)).isInstanceOf(PriceFetchException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260230/110000", "14050714/110000", "20261007/110000", "20261006/130000",
            "20261006/240000", "20261006/126000", "20261006/0", "null/110000", "20261006/null"})
    void missingInvalidAndFutureTradeTimesAreRejected(String dateAndTime) {
        String[] fields = dateAndTime.split("/");
        answer(quote("\"0\"", fields[0], fields[1], "30000"));
        assertThatThrownBy(() -> client.quote(ID)).isInstanceOf(PriceFetchException.class);
    }

    @Test
    void noTradesDoesNotReDateACarriedClosingPrice() {
        answer(quote("\"0\"", "20261006", "110000", "30000").replace("\"pDrCotVal\":31000", "\"qTotTran5J\":0"));
        assertThatThrownBy(() -> client.quote(ID)).isInstanceOf(PriceFetchException.class);
    }

    private void answer(String body) {
        when(http.get(any(), any(), any())).thenReturn(body);
    }

    private static String quote(String id, String day, String time, String price) {
        return "{\"closingPriceInfo\":{\"insCode\":" + id + ",\"dEven\":" + day + ",\"hEven\":" + time
                + ",\"pClosing\":" + price + ",\"pDrCotVal\":31000}}";
    }
}
