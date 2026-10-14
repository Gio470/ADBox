package dev.adbox.toolkit;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

public interface AdbChannel extends Closeable {
    InputStream input();
    OutputStream output();
}
