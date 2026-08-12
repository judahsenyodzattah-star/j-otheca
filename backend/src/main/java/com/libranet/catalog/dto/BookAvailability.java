package com.libranet.catalog.dto;

/** Copy counts attached to book responses. */
public record BookAvailability(long total, long available) {

    public static final BookAvailability NONE = new BookAvailability(0, 0);
}
