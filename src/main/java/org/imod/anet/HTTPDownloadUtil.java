package org.imod.anet;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.Enumeration;


/**
 * A utility that downloads a file from a URL.
 *
 * @author www.codejava.net
 *
 */
public class HTTPDownloadUtil {

    private HttpURLConnection httpConn;

    /**
     * hold input stream of HttpURLConnection
     */
    private InputStream inputStream;

    private String fileName;
    private int contentLength;

    /**
     * Downloads a file from a URL
     *
     * @param fileURL
     *            HTTP URL of the file to be downloaded
     * @throws IOException
     */
     public void extractFolder(String zipFile,String extractFolder) throws IOException
     {
         try
         {
             int BUFFER = 2048;
             File file = new File(zipFile);

             ZipFile zip = new ZipFile(file);
             String newPath = extractFolder;

             new File(newPath).mkdir();
             Enumeration zipFileEntries = zip.entries();

             // Process each entry
             while (zipFileEntries.hasMoreElements())
             {
                 // grab a zip file entry
                 ZipEntry entry = (ZipEntry) zipFileEntries.nextElement();
                 String currentEntry = entry.getName();

                 File destFile = new File(newPath, currentEntry);
                 //destFile = new File(newPath, destFile.getName());
                 File destinationParent = destFile.getParentFile();

                 // create the parent directory structure if needed
                 destinationParent.mkdirs();

                 if (!entry.isDirectory())
                 {
                     BufferedInputStream is = new BufferedInputStream(zip
                     .getInputStream(entry));
                     int currentByte;
                     // establish buffer for writing file
                     byte data[] = new byte[BUFFER];

                     // write the current file to disk
                     FileOutputStream fos = new FileOutputStream(destFile);
                     BufferedOutputStream dest = new BufferedOutputStream(fos,
                     BUFFER);

                     // read and write until last byte is encountered
                     while ((currentByte = is.read(data, 0, BUFFER)) != -1) {
                         dest.write(data, 0, currentByte);
                     }
                     dest.flush();
                     dest.close();
                     is.close();
                 }


             }
         }
         catch (Exception e)
         {
             throw new IOException("ERROR: "+e.getMessage());
         }

     }
    public void downloadFile(String fileURL) throws IOException {
        URL url = new URL(fileURL);
        httpConn = (HttpURLConnection) url.openConnection();
        int responseCode = httpConn.getResponseCode();

        // always check HTTP response code first
        if (responseCode == HttpURLConnection.HTTP_OK) {
            String disposition = httpConn.getHeaderField("Content-Disposition");
            String contentType = httpConn.getContentType();
            contentLength = httpConn.getContentLength();

            if (disposition != null) {
                // extracts file name from header field
                int index = disposition.indexOf("filename=");
                if (index > 0) {
                    fileName = disposition.substring(index + 10,
                            disposition.length() - 1);
                }
            } else {
                // extracts file name from URL
                fileName = fileURL.substring(fileURL.lastIndexOf("/") + 1,
                        fileURL.length());
            }

            // output for debugging purpose only
            // System.out.println("Content-Type = " + contentType);
            // System.out.println("Content-Disposition = " + disposition);
            // System.out.println("Content-Length = " + contentLength);
            // System.out.println("fileName = " + fileName);

            // opens input stream from the HTTP connection
            inputStream = httpConn.getInputStream();

        } else {
            throw new IOException(
                    "No file to download. Server replied HTTP code: "
                            + responseCode);
        }
    }

    public void disconnect() throws IOException {
        inputStream.close();
        httpConn.disconnect();
    }

    public String getFileName() {
        return this.fileName;
    }

    public int getContentLength() {
        return this.contentLength;
    }

    public InputStream getInputStream() {
        return this.inputStream;
    }
}
