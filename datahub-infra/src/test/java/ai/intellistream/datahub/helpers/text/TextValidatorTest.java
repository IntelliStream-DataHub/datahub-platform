// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextValidatorTest {

    @Test
    void validateAZSnakeLowerCasedAndDigits() {
        boolean r1 = TextValidator.validateAZSnakeLowerCasedAndDigits("basement_airthings_radon1_bigint");
        boolean r2 = TextValidator.validateAZSnakeLowerCasedAndDigits("BASE_airthings_radon1_bigint");
        boolean r3 = TextValidator.validateAZSnakeLowerCasedAndDigits("BASE_airthings_$radon1_bigint");
        boolean r4 = TextValidator.validateAZSnakeLowerCasedAndDigits("basement_airthings_carbon1_bigint");
        boolean r5 = TextValidator.validateAZSnakeLowerCasedAndDigits("_basement_airthings_carbon1_bigint");
        boolean r6 = TextValidator.validateAZSnakeLowerCasedAndDigits("1_basement_airthings_carbon1_bigint");
        boolean r7 = TextValidator.validateAZSnakeLowerCasedAndDigits("switch-backbone-0-01-arista");

        assert r1;
        assert !r2;
        assert !r3;
        assert r4;
        assert !r5;
        assert r6;
        assert r7;
    }

    @Test
    void validateExternalIdCharset_acceptsIndustrialTagFormats() {
        // The whole point of the change: the identifiers our target industries already maintain.
        assert TextValidator.validateExternalIdCharset("COM-99-PT-1034");   // ISA-5.1 / NORSOK tag
        assert TextValidator.validateExternalIdCharset("=K1-M3+B02");       // IEC 81346 designation
        assert TextValidator.validateExternalIdCharset("P.101");
        assert TextValidator.validateExternalIdCharset("ns:sensor_01");
        // Every previously-valid snake_case id stays valid — verbatim is a superset.
        assert TextValidator.validateExternalIdCharset("basement_airthings_radon1_bigint");
        assert TextValidator.validateExternalIdCharset("1_starts_with_digit");
    }

    @Test
    void validateExternalIdCharset_rejectsWhitespaceSlashAndControlCharacters() {
        assert !TextValidator.validateExternalIdCharset("Pump-A 01");   // space
        assert !TextValidator.validateExternalIdCharset("pump\tA");     // tab
        assert !TextValidator.validateExternalIdCharset("a/b");         // would break path segmentation
        assert !TextValidator.validateExternalIdCharset("pump\u0007a"); // control character
        assert !TextValidator.validateExternalIdCharset("pump$a");      // outside the charset
        assert !TextValidator.validateExternalIdCharset("");
        assert !TextValidator.validateExternalIdCharset(null);
    }

    @Test
    void validateExternalIdCharset_doesNotRewriteDashes() {
        // The old rule folded '-' to '_' *before* validating, so a caller mirroring a plant tag
        // silently got a different string stored. Nothing here mutates: this is a predicate.
        String tag = "COM-99-PT-1034";
        assert TextValidator.validateExternalIdCharset(tag);
        assertEquals("COM-99-PT-1034", tag);
    }

    @Test
    void test2SnakeUpperCased(){

        String name1 = TextValidator.toSnakeUpperCased("LabEl ForM");
        String name2 = TextValidator.toSnakeUpperCased("LabEl-ForM");
        String name3 = TextValidator.toSnakeUpperCased("12LabEl-ForM");
        String name4 = TextValidator.toSnakeUpperCased("BELONGS_TO");
        String name5 = TextValidator.toSnakeUpperCased("BELONGS-TO");
        String name6 = TextValidator.toSnakeUpperCased("LabEl ForM VALO");
        String name7 = TextValidator.toSnakeUpperCased("44LabEl 2345 VALO");
        String name8 = TextValidator.toSnakeUpperCased("LabEl?:ForM");
        String name9 = TextValidator.toSnakeUpperCased("LabEl?:F$%&orM");

        assert name1.equals("LABEL_FORM");
        assert name2.equals("LABEL_FORM");
        assert name3.equals("LABEL_FORM");
        assert name4.equals("BELONGS_TO");
        assert name5.equals("BELONGS_TO");
        assert name6.equals("LABEL_FORM_VALO");
        assert name7.equals("LABEL_2345_VALO");
        assert name8.equals("LABEL_FORM");
        assert name9.equals("LABEL_F_ORM");
    }

    @Test
    void toSnakeUpperCased_nullAndBlankPassThroughUnchanged(){
        // Must not NPE on null and must not normalise a blank into a non-blank token, so a
        // downstream @NotBlank check still sees the value as blank.
        assertNull(TextValidator.toSnakeUpperCased(null));
        assertEquals("", TextValidator.toSnakeUpperCased(""));
        assertEquals("   ", TextValidator.toSnakeUpperCased("   "));
    }

    @Test
    void toSnakeLowerCasedAllowStartWithDigits_nullAndBlankPassThroughUnchanged(){
        assertNull(TextValidator.toSnakeLowerCasedAllowStartWithDigits(null));
        assertEquals("", TextValidator.toSnakeLowerCasedAllowStartWithDigits(""));
        assertEquals("   ", TextValidator.toSnakeLowerCasedAllowStartWithDigits("   "));
    }

    @Test
    void pathName_acceptsSimpleRelativePath() {
        assertEquals("foo/bar", TextValidator.pathName("foo/bar"));
    }

    @Test
    void pathName_preservesLeadingSlash() {
        assertEquals("/foo/bar", TextValidator.pathName("/foo/bar"));
    }

    @Test
    void pathName_foldsBackslashesToForwardSlashes() {
        assertEquals("foo/bar/baz", TextValidator.pathName("foo\\bar\\baz"));
    }

    @Test
    void pathName_collapsesDuplicateSlashes() {
        assertEquals("foo/bar", TextValidator.pathName("foo//bar"));
        assertEquals("foo/bar", TextValidator.pathName("foo///bar"));
    }

    @Test
    void pathName_collapsesMixedSlashes() {
        assertEquals("foo/bar", TextValidator.pathName("foo\\/bar"));
        assertEquals("foo/bar", TextValidator.pathName("foo/\\bar"));
    }

    @Test
    void pathName_stripsTrailingSlash() {
        assertEquals("foo/bar", TextValidator.pathName("foo/bar/"));
    }

    @Test
    void pathName_allowsDotAsSegmentCharacter() {
        assertEquals("foo.bar/file.txt", TextValidator.pathName("foo.bar/file.txt"));
        assertEquals(".hidden", TextValidator.pathName(".hidden"));
    }

    @Test
    void pathName_rejectsDoubleDotSegment() {
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName(".."));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("/../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo/../bar"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo/bar/.."));
    }

    @Test
    void pathName_rejectsSingleDotSegment() {
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("."));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("./foo"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo/./bar"));
    }

    @Test
    void pathName_rejectsReservedTmpDirSegment() {
        // The upload-staging directory name is reserved; a user must not be able to create a folder
        // with it at any depth, or their content could collide with the staging area.
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName(TextValidator.RESERVED_TMP_DIR));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("/" + TextValidator.RESERVED_TMP_DIR));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo/" + TextValidator.RESERVED_TMP_DIR));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName(TextValidator.RESERVED_TMP_DIR + "/bar"));
        // A name that merely contains the reserved string is still fine — only an exact segment match is reserved.
        assertEquals("my.tmp", TextValidator.pathName("my.tmp"));
        assertEquals(".tmpfile", TextValidator.pathName(".tmpfile"));
    }

    @Test
    void pathName_rejectsBackslashTraversalAfterFolding() {
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("..\\etc"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo\\..\\bar"));
    }

    @Test
    void pathName_rejectsIllegalCharacters() {
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo bar"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo;rm -rf"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo$bar"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo\u0000bar"));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("foo\nbar"));
    }

    @Test
    void pathName_rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName(null));
    }

    @Test
    void pathName_rejectsEmptyAndAllSlashInputs() {
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName(""));
        assertThrows(IllegalArgumentException.class, () -> TextValidator.pathName("   "));
    }

    @Test
    void pathName_allowsRootAlone() {
        assertEquals("/", TextValidator.pathName("/"));
        assertEquals("/", TextValidator.pathName("//"));
    }
}