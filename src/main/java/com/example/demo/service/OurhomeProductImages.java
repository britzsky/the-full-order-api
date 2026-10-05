package com.example.demo.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Main product photo only; the label image remains in provider_attributes. */
final class OurhomeProductImages {
    private OurhomeProductImages() { }
    static Map<String,Object> from(Map<String,String> product) {
        var result=new LinkedHashMap<String,Object>();
        result.put("images_provided",product.containsKey("img1") || product.containsKey("imageRoute"));
        String image=usable(product.get("img1"));
        if(image==null) image=usable(product.get("imageRoute"));
        result.put("image_url",image);
        // No documented thumbnail contract: reuse the main image, never substitute the label.
        result.put("thumbnail_url",image);
        return result;
    }
    private static String usable(String value) {
        if(value==null || value.isBlank() || value.toLowerCase(Locale.ROOT).contains("noimg")) return null;
        try {
            URI uri=URI.create(value.trim());
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) return null;
            return uri.toASCIIString().length()<=2048 ? uri.toASCIIString() : null;
        } catch(IllegalArgumentException e) { return null; }
    }
}
