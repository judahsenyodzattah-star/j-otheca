package com.jotheca.media.model;

/** What an uploaded image is for. Drives role checks and public visibility (avatars stay private). */
public enum MediaKind {
    BOOK_COVER,
    LIBRARY_LOGO,
    LIBRARY_BANNER,
    USER_AVATAR
}
