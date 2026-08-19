package com.coreintra.app.api.install;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * What to create when opening an empty installation.
 *
 * <p>Small on purpose: one company, one master, and how that company's 대표
 * authority is exercised. Units, people, positions and books are ordinary work
 * for the master to do afterwards, through endpoints that check permissions like
 * everything else.
 *
 * <p>There is no password field. There is no field that could become one.
 */
@Schema(description = "The first company and the first master account.")
public class InstallationRequest {

    @NotBlank
    @Size(max = 40)
    @Schema(description = "Short stable code for the company, e.g. HANBIT", example = "HANBIT")
    private String companyCode;

    @NotBlank
    @Size(max = 200)
    @Schema(example = "한빛산업 주식회사")
    private String companyNameKo;

    @Size(max = 200)
    @Schema(example = "Hanbit Industries Co., Ltd.")
    private String companyNameEn;

    @Size(max = 40)
    @Schema(description = "사업자등록번호", example = "220-81-45678")
    private String businessRegistrationNumber;

    @Size(max = 12)
    @Schema(description = "The book's base currency, used only when accounting is on.",
            example = "KRW")
    private String baseCurrencyCode;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Schema(description = "설립일", example = "2018-03-02")
    private LocalDate establishedOn;

    @NotBlank
    @Size(max = 100)
    @Schema(description = "How the first master signs in. There is no password.",
            example = "daepyo")
    private String masterUsername;

    @NotBlank
    @Size(max = 200)
    @Schema(example = "김서연")
    private String masterDisplayName;

    /**
     * 각자대표 or 공동대표.
     *
     * <p>Defaulted to 각자대표 with one representative, which is what a newly
     * incorporated company has unless it says otherwise. A 공동대표 quorum of one
     * is refused with an explanation rather than quietly accepted.
     */
    @Schema(description = "SEVERAL (각자대표) or JOINT (공동대표)", example = "SEVERAL",
            allowableValues = {"SEVERAL", "JOINT"})
    private String representationMode = "SEVERAL";

    @Min(1)
    @Schema(description = "How many 대표 signatures a representative step needs. "
            + "Must be at least 2 under JOINT.", example = "1")
    private int requiredApprovals = 1;

    @Min(1)
    @Schema(description = "How many 대표이사 the company has designated.", example = "1")
    private int designatedRepresentatives = 1;

    public String getCompanyCode() {
        return companyCode;
    }

    public void setCompanyCode(String value) {
        this.companyCode = value;
    }

    public String getCompanyNameKo() {
        return companyNameKo;
    }

    public void setCompanyNameKo(String value) {
        this.companyNameKo = value;
    }

    public String getCompanyNameEn() {
        return companyNameEn;
    }

    public void setCompanyNameEn(String value) {
        this.companyNameEn = value;
    }

    public String getBusinessRegistrationNumber() {
        return businessRegistrationNumber;
    }

    public void setBusinessRegistrationNumber(String value) {
        this.businessRegistrationNumber = value;
    }

    public String getBaseCurrencyCode() {
        return baseCurrencyCode;
    }

    public void setBaseCurrencyCode(String value) {
        this.baseCurrencyCode = value;
    }

    public LocalDate getEstablishedOn() {
        return establishedOn;
    }

    public void setEstablishedOn(LocalDate value) {
        this.establishedOn = value;
    }

    public String getMasterUsername() {
        return masterUsername;
    }

    public void setMasterUsername(String value) {
        this.masterUsername = value;
    }

    public String getMasterDisplayName() {
        return masterDisplayName;
    }

    public void setMasterDisplayName(String value) {
        this.masterDisplayName = value;
    }

    public String getRepresentationMode() {
        return representationMode;
    }

    public void setRepresentationMode(String value) {
        this.representationMode = value;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public void setRequiredApprovals(int value) {
        this.requiredApprovals = value;
    }

    public int getDesignatedRepresentatives() {
        return designatedRepresentatives;
    }

    public void setDesignatedRepresentatives(int value) {
        this.designatedRepresentatives = value;
    }
}
