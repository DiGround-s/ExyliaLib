package net.exylia.lib.util;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QrCodesTest {

    @Test
    void mapPixelsScanBackToTheText() throws Exception {
        String text = Totp.uri("Exylia Network", "SomeLongStaffName", Totp.newSecret());
        byte[] pixels = QrCodes.mapPixels(text);
        LuminanceSource source = new LuminanceSource(QrCodes.MAP_SIZE, QrCodes.MAP_SIZE) {
            @Override
            public byte[] getRow(int y, byte[] row) {
                byte[] out = row != null && row.length >= getWidth() ? row : new byte[getWidth()];
                for (int x = 0; x < getWidth(); x++) {
                    out[x] = (byte) (pixels[y * QrCodes.MAP_SIZE + x] == QrCodes.MAP_BLACK ? 0 : 255);
                }
                return out;
            }

            @Override
            public byte[] getMatrix() {
                byte[] out = new byte[getWidth() * getHeight()];
                for (int y = 0; y < getHeight(); y++) {
                    System.arraycopy(getRow(y, null), 0, out, y * getWidth(), getWidth());
                }
                return out;
            }
        };
        String read = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
        assertEquals(text, read);
    }
}
