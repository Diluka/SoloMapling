package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.constants.game.GameConstants;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLDomMapleData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.beidou.CharacterTestContext;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Real WZ XML and the geometry loader must not inherit the business price decimal locale. */
class BotNavigationMapLoaderTest {
    @TempDir Path directory;

    @Test void groundSlipperinessKeepsWzDecimalsAndTheOrdinaryDefaultWithCommaPrices() throws Exception {
        CharacterTestContext.install();
        var parserField = GameConstants.class.getDeclaredField("nfParser");
        parserField.setAccessible(true);
        var parser = (DecimalFormat) parserField.get(null);
        synchronized (GameConstants.class) {
            var original = parser.getDecimalFormatSymbols();
            parser.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.FRANCE));
            try {
                var values = List.of("<float name=\"fs\" value=\"0.2\"/>",
                        "<float name=\"fs\" value=\"1.0\"/>", "");
                for (int index = 0; index < values.size(); index++) {
                    var xmlFile = directory.resolve("map-" + index + ".xml");
                    Files.writeString(xmlFile, """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <imgdir name="map">
                              <imgdir name="info">
                                <int name="returnMap" value="10000"/>
                                <int name="fieldLimit" value="0"/>
                                <int name="VRTop" value="-100"/><int name="VRBottom" value="100"/>
                                <int name="VRLeft" value="-100"/><int name="VRRight" value="100"/>
                                %s
                              </imgdir>
                              <imgdir name="portal"/><imgdir name="foothold"/><imgdir name="ladderRope"/>
                            </imgdir>
                            """.formatted(values.get(index)));
                    try (var input = new FileInputStream(xmlFile.toFile());
                         var providers = mockStatic(DataProviderFactory.class)) {
                        var data = new XMLDomMapleData(input, directory);
                        var provider = mock(DataProvider.class);
                        when(provider.getData(anyString())).thenReturn(data);
                        providers.when(() -> DataProviderFactory.getDataProvider(WZFiles.MAP)).thenReturn(provider);
                        var map = BotNavigationMapLoader.loadMapGeometry(98_983_101 + index);
                        assertEquals(index == 0 ? 0.2f : 1.0f, map.getFootholdSpeed());
                    }
                }
            } finally {
                parser.setDecimalFormatSymbols(original);
            }
        }
    }
}
