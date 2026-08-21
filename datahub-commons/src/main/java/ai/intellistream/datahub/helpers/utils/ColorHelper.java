// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import java.awt.*;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorHelper {

    private static final String HEX_WEBCOLOR_PATTERN= "^#([a-fA-F0-9]{6}|[a-fA-F0-9]{3})$";

    private static final Pattern pattern = Pattern.compile(HEX_WEBCOLOR_PATTERN);

    public static String generateRandomBrightColor(){
        Random random = new Random();
        final float hue = random.nextFloat();
        final float saturation = (random.nextInt(5000) + 1000) / 6000f;
        //final float luminance = 0.65f; //1.0 for brighter, 0.0 for black
        float luminance = 0.5f + random.nextFloat() * (0.7f - 0.5f);
        final Color color = Color.getHSBColor(hue, saturation, luminance);

        String red = Integer.toHexString(color.getRed());
        String green = Integer.toHexString(color.getGreen());
        String blue = Integer.toHexString(color.getBlue());

        return "#" +
                (red.length() == 1? "0" + red : red) +
                (green.length() == 1? "0" + green : green) +
                (blue.length() == 1? "0" + blue : blue);
    }

    public static boolean validateHTML(String s){
        Matcher matcher = ColorHelper.pattern.matcher(s);
        return matcher.matches();
    }
}
