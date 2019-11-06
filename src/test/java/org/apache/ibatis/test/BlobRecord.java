package org.apache.ibatis.test;

public class BlobRecord {
    private int id;
    private byte[] blob;

    public BlobRecord(int id, byte[] blob) {
        super();
        this.id = id;
        this.blob = blob;
    }

    public BlobRecord(int id, Byte[] blob) {
        super();
        this.id = id;
        final byte[] newBytes = new byte[blob.length];
        for (int i = 0; i < blob.length; i++) {
            Byte b = blob[i];
            newBytes[i] = b;
        }
        this.blob = newBytes;
    }

    public int getId() {
        return id;
    }

    public byte[] getBlob() {
        return blob;
    }
}
