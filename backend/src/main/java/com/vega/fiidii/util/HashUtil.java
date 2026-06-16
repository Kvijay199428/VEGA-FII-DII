package com.vega.fiidii.util;

import com.vega.fiidii.model.InstitutionalFlowRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {
    public static String generateSourceHash(InstitutionalFlowRecord record) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = record.getCategory() + record.getDataType() + record.getTimeStamp() +
                    record.getBuyAmount() + record.getSellAmount() +
                    record.getBuyContracts() + record.getSellContracts() +
                    record.getOiContracts() + record.getOiAmount() +
                    record.getTotalLongContracts() + record.getTotalShortContracts() +
                    record.getTotalCallLongContracts() + record.getTotalPutLongContracts() +
                    record.getTotalCallShortContracts() + record.getTotalPutShortContracts();
            
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize SHA-256 algorithm", e);
        }
    }
}