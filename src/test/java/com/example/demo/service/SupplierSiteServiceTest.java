package com.example.demo.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import com.example.demo.mapper.SupplierIntegrationMapper;
import com.example.demo.mapper.SupplierSiteMapper;

class SupplierSiteServiceTest {
    final SupplierSiteMapper mapper=mock(SupplierSiteMapper.class);
    final SupplierIntegrationMapper suppliers=mock(SupplierIntegrationMapper.class);
    final SupplierSiteService service=new SupplierSiteService(mapper,suppliers);

    @BeforeEach void setup(){
        when(suppliers.supplierIdByCode(anyMap())).thenReturn(1L);
        when(mapper.masterSite(anyMap())).thenReturn(Map.of("external_site_code","A1","external_site_name","우리누리요양원_더채움","use_yn","Y"));
        when(mapper.otherAccountsUsing(anyMap())).thenReturn(List.of());
        when(mapper.lockAccountMappings(anyMap())).thenReturn(List.of());
        when(mapper.mappingId(anyMap())).thenReturn(9L);
    }
    Map<String,Object> row(long id,String code,String active){return Map.of("supplier_account_site_id",id,"external_site_code",code,"external_site_name","이전","active_yn",active);}

    @Test void replacingSiteDeactivatesPreviousInsteadOfDeleting(){
        when(mapper.lockAccountMappings(anyMap())).thenReturn(List.of(row(3,"OLD","Y"),row(4,"OLDER","N")));
        var result=service.assign("ACC","WELSTORY","A1",false,"u");
        verify(mapper).deactivateMapping(argThat(p->Long.valueOf(3).equals(p.get("supplier_account_site_id"))));
        verify(mapper,never()).deactivateMapping(argThat(p->Long.valueOf(4).equals(p.get("supplier_account_site_id"))));
        verify(mapper).insertMapping(anyMap());
        assertThat(result.get("supplier_account_site_id")).isEqualTo(9L);
    }
    @Test void existingInactiveRowIsReactivatedNotDuplicated(){
        when(mapper.activateMapping(anyMap())).thenReturn(1);
        service.assign("ACC","OURHOME","A1",false,"u");
        verify(mapper,never()).insertMapping(anyMap());
    }
    @Test void siteUsedByAnotherAccountNeedsConfirmation(){
        when(mapper.otherAccountsUsing(anyMap())).thenReturn(List.of("OTHER"));
        assertThatThrownBy(()->service.assign("ACC","WELSTORY","A1",false,"u"))
            .isInstanceOfSatisfying(SupplierSiteService.SharedSiteException.class,e->assertThat(e.accountIds()).containsExactly("OTHER"));
        verify(mapper,never()).deactivateMapping(anyMap()); verify(mapper,never()).insertMapping(anyMap());
        service.assign("ACC","WELSTORY","A1",true,"u");
        verify(mapper).insertMapping(anyMap());
    }
    @Test void rejectsUnknownOrExcludedSitesAndUnsupportedSupplier(){
        when(mapper.masterSite(anyMap())).thenReturn(null);
        assertThatThrownBy(()->service.assign("ACC","WELSTORY","NOPE",false,"u")).hasMessageContaining("목록에 없는");
        when(mapper.masterSite(anyMap())).thenReturn(Map.of("external_site_name","본사_더채움","use_yn","N"));
        assertThatThrownBy(()->service.assign("ACC","WELSTORY","HQ",false,"u")).hasMessageContaining("매핑 제외");
        assertThatThrownBy(()->service.assign("ACC","CJ","A1",false,"u")).hasMessageContaining("지원하지 않는");
    }
    @Test void blankCodeUnassigns(){
        when(mapper.lockAccountMappings(anyMap())).thenReturn(List.of(row(3,"OLD","Y")));
        var result=service.assign("ACC","WELSTORY","",false,"u");
        verify(mapper).deactivateMapping(anyMap()); verify(mapper,never()).insertMapping(anyMap());
        assertThat(result.get("supplier_account_site_id")).isNull();
    }
    @Test void catalogSyncRefusesToSwitchAnAccountToADifferentSite(){
        when(mapper.lockAccountMappings(anyMap())).thenReturn(List.of(row(3,"FU0UR","Y")));
        assertThatThrownBy(()->service.requireOrAssign("ACC","OURHOME","FU0UM","u")).hasMessageContaining("FU0UR").hasMessageContaining("사업장 매핑");
        verify(mapper,never()).deactivateMapping(anyMap());
    }
    @Test void catalogSyncKeepsAlreadySharedMappingWithoutAskingAgain(){
        when(mapper.lockAccountMappings(anyMap())).thenReturn(List.of(row(3,"A1","Y")));
        when(mapper.otherAccountsUsing(anyMap())).thenReturn(List.of("OTHER"));
        when(mapper.activateMapping(anyMap())).thenReturn(1);
        assertThat(service.requireOrAssign("ACC","OURHOME","A1","u")).isEqualTo(9L);
    }
    @Test void emptyProviderListKeepsExistingMaster(){
        assertThatThrownBy(()->service.saveProviderSites("WELSTORY",List.of(),"u")).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).markUnlisted(anyMap());
    }
}
