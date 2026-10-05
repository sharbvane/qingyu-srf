package com.qingyu.core;

/** A decoder candidate. Its id is valid only for the snapshot that contains it. */
public final class Candidate {
    public final int id;
    public final String text;

    public Candidate(int id, String text) {
        this.id = id;
        this.text = text == null ? "" : text;
    }
}
