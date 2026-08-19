package com.coreintra.documents.service;

/**
 * What removing a font would cost, in both languages.
 *
 * <p>Exists so "removing a font that documents reference warns with the affected count
 * first" is a value a caller has to hold, not a log line nobody reads. {@link
 * FontStoreService#remove} takes the count back and refuses if it has changed, so the
 * warning cannot be skipped and cannot be stale.
 */
public final class FontRemovalImpact {

    private final String fontId;

    private final String family;

    private final long affectedRenderCount;

    FontRemovalImpact(String fontId, String family, long affectedRenderCount) {
        this.fontId = fontId;
        this.family = family;
        this.affectedRenderCount = affectedRenderCount;
    }

    public String fontId() {
        return fontId;
    }

    public String family() {
        return family;
    }

    /** Archived renders made with this family. Each one becomes unreproducible. */
    public long affectedRenderCount() {
        return affectedRenderCount;
    }

    public boolean isSafe() {
        return affectedRenderCount == 0;
    }

    public String warningKo() {
        if (isSafe()) {
            return "\"" + family + "\" 글꼴을 사용한 출력물이 없습니다. 삭제해도 안전합니다.";
        }
        return "\"" + family + "\" 글꼴은 이미 출력된 문서 " + affectedRenderCount
                + "건에 사용되었습니다. 삭제하면 해당 문서를 동일하게 다시 출력할 수 없습니다.";
    }

    public String warningEn() {
        if (isSafe()) {
            return "No archived render uses \"" + family + "\". Removing it is safe.";
        }
        return affectedRenderCount + " archived render(s) were made with \"" + family
                + "\". Removing it means none of them can be reproduced identically again.";
    }
}
