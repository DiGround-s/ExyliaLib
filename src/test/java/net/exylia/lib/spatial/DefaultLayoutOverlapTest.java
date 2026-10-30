package net.exylia.lib.spatial;

import net.exylia.lib.spatial.internal.Geometry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's default wardrobe, seen front-on from the eyes: every piece's outline projected onto
 * the board plane (nearer pieces look larger), and no two of them may cover each other.
 */
class DefaultLayoutOverlapTest {

    /** {minAcross, minUp, maxAcross, maxUp} on the board, as the eyes see it. */
    private record Box(double x0, double y0, double x1, double y1) {
        boolean overlaps(Box o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }
    }

    /** Projects points {across, up, forward} from the eyes (at the board's middle) onto the board plane. */
    private static Box project(double distance, List<double[]> points) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (double[] p : points) {
            double k = distance / (distance - p[2]);
            x0 = Math.min(x0, p[0] * k);
            x1 = Math.max(x1, p[0] * k);
            y0 = Math.min(y0, p[1] * k);
            y1 = Math.max(y1, p[1] * k);
        }
        return new Box(x0, y0, x1, y1);
    }

    /** A flat rectangle of {@code width × height} centred on a point, turned {@code turn} degrees. */
    private static List<double[]> rect(double across, double up, double forward, double turn, double width, double height) {
        double t = Math.toRadians(turn);
        List<double[]> corners = new ArrayList<>();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                double x = sx * width / 2;
                // Turned towards the viewer: the outer edge comes nearer.
                corners.add(new double[]{across + x * Math.cos(t), up + sy * height / 2, forward - x * Math.sin(t)});
            }
        }
        return corners;
    }

    /** A box standing on a point: its front and back faces. */
    private static List<double[]> solid(double across, double bottom, double forward, double width, double height, double depth) {
        List<double[]> points = new ArrayList<>(rect(across, bottom + height / 2, forward + depth / 2, 0, width, height));
        points.addAll(rect(across, bottom + height / 2, forward - depth / 2, 0, width, height));
        return points;
    }

    private static Map<String, Box> boxes(SpatialLayout layout) {
        double d = layout.anchor().distance();
        double reach = layout.theme().backingReach();
        Map<String, Box> boxes = new LinkedHashMap<>();
        layout.panels().forEach((name, p) -> boxes.put(name,
                project(d, rect(p.across(), p.up(), p.forward(), p.turn(), p.width() + reach * 2, p.height() + reach * 2))));

        SpatialLayout.Preview preview = layout.preview();
        double s = preview.scale();
        List<double[]> standing = new ArrayList<>();
        // The pedestal (0.78 wide, 0.12 tall under the feet) and a body 1.8 tall, 0.6 wide.
        standing.addAll(solid(preview.at().get(0), preview.at().get(1) - 0.12, preview.at().get(2), 0.78, 0.12, 0.78));
        standing.addAll(solid(preview.at().get(0), preview.at().get(1), preview.at().get(2), 0.6 * s, 1.8 * s, 0.6 * s));
        boxes.put("preview", project(d, standing));

        SpatialLayout.Carousel row = layout.carousel();
        double width = Math.min(row.spacing() * 0.92, 0.6);
        for (int i = 0; i < row.slots(); i++) {
            double[] slot = Geometry.carousel(i, row.slots(), row.spacing(), row.centreGap(), row.curve());
            // From under its state line (-0.45) to the top of its item (+0.24).
            boxes.put("carousel/" + i, project(d, rect(row.at().get(0) + slot[0], row.at().get(1) - 0.105,
                    row.at().get(2) + slot[1], 0, width, 0.69)));
        }
        // The page line and its arrows under the middle.
        boxes.put("carousel/page", project(d, rect(row.at().get(0), row.at().get(1) - 0.34, row.at().get(2), 0, 1.0, 0.16)));
        return boxes;
    }

    @Test
    void theDefaultLayoutHasNothingInFrontOfAnythingElse() {
        Map<String, Box> boxes = boxes(new SpatialLayout());
        List<String> names = List.copyOf(boxes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                if (names.get(i).startsWith("carousel/") && names.get(j).startsWith("carousel/")) continue;
                Box a = boxes.get(names.get(i));
                Box b = boxes.get(names.get(j));
                assertTrue(!a.overlaps(b), names.get(i) + " " + a + " overlaps " + names.get(j) + " " + b);
            }
        }
    }

    @Test
    void theDefaultLayoutFitsAVerticalViewOf70Degrees() {
        double half = new SpatialLayout().anchor().distance() * Math.tan(Math.toRadians(35));
        boxes(new SpatialLayout()).forEach((name, box) ->
                assertTrue(box.y0() > -half && box.y1() < half, name + " " + box + " leaves the view (±" + half + ")"));
    }
}
