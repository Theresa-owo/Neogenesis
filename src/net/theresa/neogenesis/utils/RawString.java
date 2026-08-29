package net.theresa.neogenesis.utils;

public class RawString {

    private final String rawString;

    public RawString(String rawString) {
        this.rawString = rawString;
    }

    public String raw() {
        return this.rawString;
    }

    public String plain() {
        return this.rawString.replaceAll("\\u00a7.", "");
    }

    public String trim() {
        return this.plain().trim();
    }

    public String ascii() {
        return this.plain().replaceAll("[^\\x20-\\x7e]", "").trim();
    }

    public boolean isNull() {
        return this.rawString == null;
    }

    public boolean isEmpty() {
        return this.isNull() || this.rawString.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("RawString[%s]", this.rawString);
    }

}