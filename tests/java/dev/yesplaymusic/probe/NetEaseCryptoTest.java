package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

/** Protocol vectors; the expected ciphertexts were generated independently with OpenSSL. */
final class NetEaseCryptoTest {
    private NetEaseCryptoTest() {}

    static void run() throws Exception {
        String eapiJson = "{\"id\":\"123\",\"tv\":-1,\"lv\":-1,\"rv\":-1,\"kv\":-1,\"_nmclfl\":1}";
        String expectedEapi = "9B1595479B79266065903BA79E9883029338432CD7D98D1A3626E997A2C53121"
                + "B007291DAF17B40485008C58BC0845A710AE176674DAEB92F9619C2C05ED848B"
                + "6A59632EA783C3DC3E23B867E9FFD25E9B13F34AFEC3DA8E69251F8EB21D1A"
                + "117CB716A1E9A17127BACA5DA825FE1DDDDCE2807116DE1D2CCC7565162A1F"
                + "073AA6073F78C543B0A89158359B0E15B6F6";
        that(expectedEapi.equals(NetEaseCrypto.encryptEApiText("/api/song/lyric", eapiJson)), "EAPI vector");
        that("echuH06dDs7OxMA2egejtgm/+RGrxeYbDvyAxb6PxreZ/A/gWb7NtSIKuLJ4I5fh".equals(
                NetEaseCrypto.encryptWeApiText("{\"csrf_token\":\"\",\"type\":3}", "0123456789abcdef")), "WeAPI AES vector");
        that(NetEaseCrypto.encryptSecret("0123456789abcdef").length() == 256, "WeAPI RSA size");
        that(NetEaseCrypto.withEapiHeaderJson("{\"field\":\"value\"}",
                        "{\"os\":\"android\",\"appver\":\"test\",\"__csrf\":\"\"}")
                .equals("{\"field\":\"value\",\"header\":{\"os\":\"android\",\"appver\":\"test\",\"__csrf\":\"\"},\"e_r\":false}"),
                "EAPI header and e_r envelope");
    }
}
