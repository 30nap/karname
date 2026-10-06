package ir.karname.io;

import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.ApiException;
import ir.karname.io.BackupService.Backup;
import ir.karname.io.BackupService.RestoreSummary;
import ir.karname.io.StatementImportService.AmountUnit;
import ir.karname.io.StatementImportService.CommitRequest;
import ir.karname.io.StatementImportService.CommitResult;
import ir.karname.io.StatementImportService.DateStyle;
import ir.karname.io.StatementImportService.Mapping;
import ir.karname.io.StatementImportService.Preview;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

/** Export (CSV, full JSON backup), restore, and bank statement import. */
@RestController
@RequestMapping("/api/v1/io")
public class IoController {

    /** The word the user types to confirm that a restore replaces all their data. */
    static final String CONFIRM_REPLACE = "REPLACE";

    private final CsvExportService csv;
    private final BackupService backups;
    private final StatementImportService statements;
    private final JsonMapper json;
    private final Clock clock;

    public IoController(CsvExportService csv, BackupService backups, StatementImportService statements, JsonMapper json, Clock clock) {
        this.csv = csv;
        this.backups = backups;
        this.statements = statements;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/transactions.csv")
    public void exportTransactions(@AuthenticationPrincipal KarnamePrincipal user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment("karname-transactions", "csv"));
        csv.writeTransactions(user.id(), from, to, response.getOutputStream());
    }

    @GetMapping("/backup")
    public ResponseEntity<Backup> backup(@AuthenticationPrincipal KarnamePrincipal user) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment("karname-backup", "json"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(backups.export(user.id()));
    }

    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RestoreSummary restore(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String confirm) throws IOException {
        if (!CONFIRM_REPLACE.equals(confirm)) {
            throw ApiException.badRequest("backup.confirmRequired");
        }
        Backup backup;
        try (InputStream in = file.getInputStream()) {
            backup = json.readValue(in, Backup.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest("backup.invalidFormat");
        }
        return backups.restore(user.id(), backup);
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Preview previewImport(@AuthenticationPrincipal KarnamePrincipal user, @RequestParam("file") MultipartFile file,
            @RequestParam long accountId,
            @RequestParam(required = false) Integer dateColumn, @RequestParam(required = false) Integer descriptionColumn,
            @RequestParam(required = false) Integer amountColumn, @RequestParam(required = false) Integer debitColumn,
            @RequestParam(required = false) Integer creditColumn, @RequestParam(required = false) DateStyle dateStyle,
            @RequestParam(required = false) AmountUnit unit, @RequestParam(required = false) Boolean hasHeader) throws IOException {
        if (file.getSize() > StatementImportService.MAX_BYTES) {
            throw ApiException.badRequest("import.tooLarge");
        }
        Mapping mapping = new Mapping(dateColumn, descriptionColumn, amountColumn, debitColumn, creditColumn, dateStyle, unit, hasHeader);
        return statements.preview(user.id(), accountId, file.getBytes(), mapping);
    }

    @PostMapping("/import/commit")
    public CommitResult commitImport(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody CommitRequest request) {
        return statements.commit(user.id(), request);
    }

    /** «karname-backup-1405-07-14.json»: the Jalali date of the export, in Latin digits for file systems. */
    private String attachment(String name, String extension) {
        String date = JalaliDate.from(LocalDate.now(clock)).toString().replace('/', '-');
        return ContentDisposition.attachment().filename(name + "-" + date + "." + extension, StandardCharsets.UTF_8).build().toString();
    }
}
