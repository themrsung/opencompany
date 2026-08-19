package com.coreintra.documents.internal.adapter.hwpx;

import com.coreintra.documents.internal.InternalDoc;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.header_xml.RefList;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ParaHeadingType;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.StyleType;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.UnderlineType;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Bullet;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Style;

/**
 * Allocates the header-level references an HWPX body needs, on demand.
 *
 * <h2>Why the styles are allocated rather than declared</h2>
 *
 * <p>In OWPML a run does not carry its own formatting: it carries
 * {@code charPrIDRef}, an index into {@code header.xml}'s character-property
 * table. Bold text is not a property of the run, it is a reference to a
 * character property that happens to have {@code <hh:bold/>} in it. The same is
 * true of paragraphs, list numbering and cell borders.
 *
 * <p>So writing "bold" means finding or creating the right table entry. Doing
 * that naively — one entry per run — produces a header with hundreds of
 * near-identical properties, which 한글 shows the user as hundreds of styles.
 * This class interns them instead: identical formatting yields the same id, so
 * a document with one bold word and a document with a thousand produce the same
 * two entries.
 *
 * <h2>What the blank scaffold already provides</h2>
 *
 * <p>{@code BlankFileMaker} is not empty. It supplies seven character
 * properties, sixteen paragraph properties, eighteen styles — including 바탕글
 * and 개요 1–7, which are the outline levels 한글 shows in its navigation pane —
 * one numbering definition, and two border fills. Headings therefore map onto
 * the built-in 개요 styles rather than onto invented ones, which is what makes
 * an exported document navigable in 한글 rather than merely visually similar.
 *
 * <p>Both supplied border fills have {@code type="NONE"} on all four edges, so
 * a table drawn with them would have no visible lines. A ruled fill is
 * allocated for table cells for that reason.
 */
final class HwpxStyles {

    /** 한글's built-in outline styles, in order: 개요 1 is style id 2. */
    private static final int FIRST_OUTLINE_STYLE_ID = 2;

    /** 개요 1–7. A deeper heading than the format has is clamped to the last. */
    private static final int OUTLINE_STYLE_COUNT = 7;

    /** The built-in numbering definition the blank scaffold ships. */
    private static final String BUILTIN_NUMBERING_ID = "1";

    private final RefList refList;
    private final Map<String, String> charPrCache = new LinkedHashMap<String, String>();
    private final Map<String, String> paraPrCache = new LinkedHashMap<String, String>();

    private String tableBorderFillId;
    private String bulletId;

    HwpxStyles(HWPXFile file) {
        this.refList = file.headerXMLFile().refList();
    }

    /** The body-text style ({@code 바탕글}) and its paragraph property. */
    String bodyStyleId() {
        return "0";
    }

    String bodyParaPrId() {
        return paraPrIdOfStyle(bodyStyleId());
    }

    /**
     * The 개요 style for a heading level.
     *
     * @param level 1-6 as {@link InternalDoc.Paragraph#headingLevel()} defines it
     */
    String outlineStyleId(int level) {
        int clamped = Math.max(1, Math.min(OUTLINE_STYLE_COUNT, level));
        return Integer.toString(FIRST_OUTLINE_STYLE_ID + clamped - 1);
    }

    /** The paragraph property a style names, so a paragraph agrees with its style. */
    String paraPrIdOfStyle(String styleId) {
        ObjectList<Style> styles = refList.styles();
        if (styles != null) {
            for (int index = 0; index < styles.count(); index++) {
                Style style = styles.get(index);
                if (styleId.equals(style.id()) && style.paraPrIDRef() != null) {
                    return style.paraPrIDRef();
                }
            }
        }
        // Defaulting to the body property keeps a document readable if a future
        // hwpxlib scaffold renumbers its styles; a dangling reference would not.
        return "0";
    }

    /**
     * A character property carrying exactly this run's emphasis.
     *
     * <p>Derived from character property 0 — the document's base — so the
     * emphasis is additive rather than a wholesale replacement of the font,
     * size and colour the rest of the document uses.
     */
    String charPrFor(InternalDoc.Run run) {
        boolean bold = run.bold();
        boolean italic = run.italic();
        boolean underline = run.underline();
        boolean strike = run.strike();
        if (!bold && !italic && !underline && !strike) {
            return "0";
        }
        String key = (bold ? "b" : "") + (italic ? "i" : "") + (underline ? "u" : "")
                + (strike ? "s" : "");
        String cached = charPrCache.get(key);
        if (cached != null) {
            return cached;
        }

        ObjectList<CharPr> properties = refList.charProperties();
        CharPr base = properties.get(0);
        CharPr derived = base.clone();
        String id = Integer.toString(nextId(idsOf(properties)));
        derived.id(id);
        if (bold) {
            derived.createBold();
        }
        if (italic) {
            derived.createItalic();
        }
        if (underline) {
            derived.createUnderline();
            derived.underline().type(UnderlineType.BOTTOM);
        }
        if (strike) {
            derived.createStrikeout();
            derived.strikeout().shape(LineType2.SOLID);
        }
        properties.add(derived);
        charPrCache.put(key, id);
        return id;
    }

    /**
     * A paragraph property for a list item at a level.
     *
     * <p>{@code listStyle} is {@code "ordered"} or {@code "bullet"}, matching
     * {@link InternalDoc.Paragraph#listStyle()}. Numbered lists reuse the
     * scaffold's numbering definition, whose seven levels already alternate
     * 1. / 가. / 1) — the Korean convention — so a list exported from this
     * system looks like one a 한글 user would have typed.
     */
    String listParaPrFor(String listStyle, int level) {
        boolean bullet = !"ordered".equals(listStyle);
        int clamped = Math.max(1, Math.min(7, level + 1));
        String key = (bullet ? "bullet" : "ordered") + ":" + clamped;
        String cached = paraPrCache.get(key);
        if (cached != null) {
            return cached;
        }

        ObjectList<ParaPr> properties = refList.paraProperties();
        ParaPr derived = findParaPr(properties, bodyParaPrId()).clone();
        String id = Integer.toString(nextId(idsOf(properties)));
        derived.id(id);
        derived.createHeading();
        derived.heading().type(bullet ? ParaHeadingType.BULLET : ParaHeadingType.NUMBER);
        derived.heading().idRef(bullet ? bulletId() : BUILTIN_NUMBERING_ID);
        derived.heading().level(Byte.valueOf((byte) (clamped - 1)));
        properties.add(derived);
        paraPrCache.put(key, id);
        return id;
    }

    /**
     * A ruled border fill for table cells.
     *
     * <p>Allocated rather than reused: the scaffold's two fills draw nothing,
     * and a table whose lines are invisible is a worse answer than no table.
     */
    String tableBorderFillId() {
        if (tableBorderFillId != null) {
            return tableBorderFillId;
        }
        ObjectList<BorderFill> fills = refList.borderFills();
        BorderFill ruled = fills.get(0).clone();
        String id = Integer.toString(nextId(idsOf(fills)));
        ruled.id(id);
        rule(ruled);
        fills.add(ruled);
        tableBorderFillId = id;
        return id;
    }

    private static void rule(BorderFill fill) {
        fill.createLeftBorder();
        fill.leftBorder().type(LineType2.SOLID);
        fill.leftBorder().width(LineWidth.MM_0_12);
        fill.leftBorder().color("#000000");
        fill.createRightBorder();
        fill.rightBorder().type(LineType2.SOLID);
        fill.rightBorder().width(LineWidth.MM_0_12);
        fill.rightBorder().color("#000000");
        fill.createTopBorder();
        fill.topBorder().type(LineType2.SOLID);
        fill.topBorder().width(LineWidth.MM_0_12);
        fill.topBorder().color("#000000");
        fill.createBottomBorder();
        fill.bottomBorder().type(LineType2.SOLID);
        fill.bottomBorder().width(LineWidth.MM_0_12);
        fill.bottomBorder().color("#000000");
    }

    /** The scaffold ships no bullet definition, so an unordered list needs one. */
    private String bulletId() {
        if (bulletId != null) {
            return bulletId;
        }
        if (refList.bullets() == null) {
            refList.createBullets();
        }
        ObjectList<Bullet> bullets = refList.bullets();
        Bullet bullet = bullets.addNew();
        String id = Integer.toString(nextId(idsOf(bullets)));
        bullet.id(id);
        bullet._char("•");
        bullet.useImage(Boolean.FALSE);
        bulletId = id;
        return id;
    }

    private static ParaPr findParaPr(ObjectList<ParaPr> properties, String id) {
        for (int index = 0; index < properties.count(); index++) {
            if (id.equals(properties.get(index).id())) {
                return properties.get(index);
            }
        }
        return properties.get(0);
    }

    /**
     * Ids are strings in OWPML but numeric in every file anyone ships, so the
     * next free one is "highest seen plus one". A non-numeric id is skipped
     * rather than crashing the export: a file we did not write is allowed to be
     * stranger than ours.
     */
    private static int nextId(String[] ids) {
        int highest = -1;
        for (int index = 0; index < ids.length; index++) {
            String id = ids[index];
            if (id == null) {
                continue;
            }
            try {
                highest = Math.max(highest, Integer.parseInt(id.trim()));
            } catch (NumberFormatException ignored) {
                // Not ours to interpret; it just cannot collide with a number.
                continue;
            }
        }
        return highest + 1;
    }

    private static String[] idsOf(ObjectList<?> list) {
        String[] ids = new String[list == null ? 0 : list.count()];
        for (int index = 0; index < ids.length; index++) {
            Object item = list.get(index);
            if (item instanceof CharPr) {
                ids[index] = ((CharPr) item).id();
            } else if (item instanceof ParaPr) {
                ids[index] = ((ParaPr) item).id();
            } else if (item instanceof BorderFill) {
                ids[index] = ((BorderFill) item).id();
            } else if (item instanceof Bullet) {
                ids[index] = ((Bullet) item).id();
            } else if (item instanceof Style) {
                ids[index] = ((Style) item).id();
            }
        }
        return ids;
    }

    /** Guards a scaffold assumption the whole mapping rests on. */
    static boolean looksLikeExpectedScaffold(HWPXFile file) {
        RefList refList = file.headerXMLFile().refList();
        if (refList == null || refList.charProperties() == null
                || refList.charProperties().count() == 0
                || refList.paraProperties() == null || refList.paraProperties().count() == 0
                || refList.styles() == null) {
            return false;
        }
        ObjectList<Style> styles = refList.styles();
        for (int index = 0; index < styles.count(); index++) {
            Style style = styles.get(index);
            if (StyleType.PARA.equals(style.type())
                    && Integer.toString(FIRST_OUTLINE_STYLE_ID).equals(style.id())) {
                return true;
            }
        }
        return false;
    }
}
