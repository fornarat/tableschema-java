/*
 *
 */
package io.frictionlessdata.tableschema.datasourceformat;

import io.frictionlessdata.tableschema.exception.TableSchemaException;
import org.apache.commons.csv.CSVFormat;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Interface for a source of tabular data.
 */
public interface DataSourceFormat {
    public static final String UTF16_BOM = "\uFEFF";
    public static final String UTF8_BOM = "\u00ef\u00bb\u00bf";
    /**
     * Returns an Iterator that returns String arrays containing
     * one row of data each.
     * @return Iterator over the data
     * @throws Exception thrown if reading the data fails
     */
    Iterator<String[]> iterator() throws Exception;

    /**
     * Returns the data headers if no headers were set or the set headers
     * @return Column headers as a String array
     */
    String[] getHeaders() throws Exception;

    /**
     * Returns the whole data as a List of String arrays, each List entry is one row
     * @return List containing the data
     * @throws Exception thrown if reading the data fails
     */
    List<String[]> data() throws Exception;

    /**
     * Write to native format
     * @param outputFile the File to write to
     * @throws Exception thrown if write operation fails
     */
    void write(File outputFile) throws Exception;

    /**
     * Write as CSV file, the `format` parameter decides on the CSV options. If it is
     * null, then the file will be written as RFC 4180 compliant CSV
     * @param out the Writer to write to
     * @param format the CSV format to use
     * @param sortedHeaders the header row names in the order in which data should be
     *                      exported
     */
    void writeCsv(Writer out, CSVFormat format, String[] sortedHeaders);

    /**
     * Write as CSV file, the `format` parameter decides on the CSV options. If it is
     * null, then the file will be written as RFC 4180 compliant CSV
     * @param outputFile the File to write to
     * @param format the CSV format to use
     * @param sortedHeaders the header row names in the order in which data should be
     *                      exported
     */
    void writeCsv(File outputFile, CSVFormat format, String[] sortedHeaders) throws Exception;

    /**
     * Signals whether extracted headers can be trusted (CSV with header row) or not
     * (JSON array of JSON objects where null values are omitted).
     * @return true if extracted headers can be trusted, false otherwise
     */
    boolean hasReliableHeaders();

    /**
     * Factory method to instantiate either a JsonArrayDataSource or a
     * CsvDataSource based on input format
     * @return DataSource created from input String
     */
    static DataSourceFormat createDataSourceFormat(String input) {
        try {
            // JSON array generation only to see if an exception is thrown -> probably CSV data
            new JSONArray(input);
            return new JsonArrayDataSourceFormat(input);
        } catch (JSONException ex) {
            // JSON parsing failed, treat it as a CSV
            return new CsvDataSourceFormat(input);
        }
    }

    /**
     * Factory method to instantiate either a {@link JsonArrayDataSourceFormat} or a
     * {@link CsvDataSourceFormat} based on input format
     * @return DataSource created from input File
     */
    static DataSourceFormat createDataSourceFormat(File input, File workDir) throws IOException {
        InputStream is;
        String content;
        if (workDir.getAbsolutePath().toLowerCase().endsWith(".zip")) {
            is = getZipFileInputStream(workDir.toPath(), input.getName());
        } else {
            Path resolvedPath = DataSourceFormat.toSecure(input.toPath(), workDir.toPath());
            is = new FileInputStream(resolvedPath.toFile());
        }
        try (BufferedReader rdr = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> lines = rdr
                    .lines()
                    .collect(Collectors.toList());
            content = String.join("\n", lines);
        }  catch (Exception ex) {
            throw new TableSchemaException(ex);
        }
        return createDataSourceFormat(content);
    }

    static InputStream getZipFileInputStream(Path inFilePath, String fileName) throws IOException {
        // Read in memory the file inside the zip.
        ZipFile zipFile = new ZipFile(inFilePath.toFile());
        ZipEntry entry = findZipEntry(zipFile, fileName);

        // Throw exception if expected datapackage.json file not found.
        if(entry == null){
            throw new TableSchemaException("The zip file does not contain the expected file: " + fileName);
        }

        return zipFile.getInputStream(entry);
    }

    /**
     * Take a ZipFile and look for the `filename` entry. If it is not on the top-level,
     * look for directories and go into them (but only one level deep) and look again
     * for the `filename` entry
     * @param zipFile the ZipFile to use for looking for the `filename` entry
     * @param fileName name of the entry we are looking for
     * @return ZipEntry if found, null otherwise
     */
    static ZipEntry findZipEntry(ZipFile zipFile, String fileName) {
        ZipEntry entry = zipFile.getEntry(fileName);
        if (null != entry)
            return entry;
        else {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                if (zipEntry.isDirectory()) {
                    entry = zipFile.getEntry(zipEntry.getName()+fileName);
                    if (null != entry)
                        return entry;
                }
            }
        }
        return null;
    }

    static CSVFormat getDefaultCsvFormat() {
        return CSVFormat.RFC4180
                .withHeader()
                .withIgnoreSurroundingSpaces(true)
                .withRecordSeparator("\n");
    }

    /**
     * Factory method to instantiate either a {@link JsonArrayDataSourceFormat} or a
     * {@link CsvDataSourceFormat}  based on input format
     * @return DataSource created from input String
     */
    static DataSourceFormat createDataSourceFormat(InputStream input) throws IOException {
        String content = null;

        // Read the file.
        try (Reader fr = new InputStreamReader(input)) {
            try (BufferedReader rdr = new BufferedReader(fr)) {
                content = rdr.lines().collect(Collectors.joining("\n"));
            }
            content = trimBOM(content);
        } catch (IOException ex) {
            throw ex;
        }

        return createDataSourceFormat(content);
    }

    static String trimBOM(String input) {
        if (null == input)
            return null;
        if( input.startsWith(UTF16_BOM)) {
            input = input.substring(1);
        } else if( input.startsWith(UTF8_BOM)) {
            input = input.substring(3);
        }
        return input;
    }

    //https://docs.oracle.com/javase/tutorial/essential/io/pathOps.html
    static Path toSecure(Path testPath, Path referencePath) throws IOException {
        // catch paths starting with "/" but on Windows where they get rewritten
        // to start with "\"
        if (testPath.startsWith(File.separator))
            throw new IllegalArgumentException("Input path must be relative");
        if (testPath.isAbsolute()){
            throw new IllegalArgumentException("Input path must be relative");
        }
        if (!referencePath.isAbsolute()) {
            throw new IllegalArgumentException("Reference path must be absolute");
        }
        if (testPath.toFile().isDirectory()){
            throw new IllegalArgumentException("Input path cannot be a directory");
        }
        final Path resolvedPath = referencePath.resolve(testPath).normalize();
        if (!Files.exists(resolvedPath))
            throw new FileNotFoundException("File "+resolvedPath.toString()+" does not exist");
        if (!resolvedPath.toFile().isFile()){
            throw new IllegalArgumentException("Input must be a file");
        }
        if (!resolvedPath.startsWith(referencePath)) {
            throw new IllegalArgumentException("Input path escapes the base path");
        }

        return resolvedPath;
    }

    enum Format {
        FORMAT_CSV("csv"),
        FORMAT_JSON("json");

        private static final Map<String, Format> lookup = new HashMap<>();
        private final String label;

        Format(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public static Format byName(String label) {
            return lookup.get(label);
        }

        /*
            Populate lookup dict at load time
         */

        static {
            for (Format env : Format.values()) {
                lookup.put(env.getLabel(), env);
            }
        }
    }
}