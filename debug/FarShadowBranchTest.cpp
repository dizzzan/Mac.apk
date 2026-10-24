#include "../runtime/translator/src/a64_shadow_page.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <libkern/OSCacheControl.h>
#include <sys/mman.h>

namespace {
constexpr size_t page = 0x4000;
uint32_t branch(uintptr_t from, uintptr_t to) {
    return 0x14000000u | (uint32_t(int64_t(to-from)/4) & 0x3ffffff);
}
uint32_t conditional(uint32_t op, uintptr_t from, uintptr_t to, unsigned bits=19) {
    return op | ((uint32_t(int64_t(to-from)/4) & ((1u<<bits)-1))<<5);
}
uint32_t adr(unsigned reg, uintptr_t from, uintptr_t to) {
    int64_t delta=to-from;
    return 0x10000000u | ((delta&3)<<29) | (((delta>>2)&0x7ffff)<<5) | reg;
}
}

int main() {
    // Keep source pages adjacent while placing execution copies beyond B's
    // reach. Source bytes are read-only and never executable: any fallback to
    // the original mapping or a trap instead of the veneer fails this test.
    constexpr size_t size=320ull*1024*1024;
    void* mapping=mmap(nullptr,size,PROT_NONE,MAP_PRIVATE|MAP_ANON,-1,0);
    assert(mapping!=MAP_FAILED);
    uintptr_t original=reinterpret_cast<uintptr_t>(mapping);
    uintptr_t shadows[]={original+32*1024*1024,original+256*1024*1024};
    auto resolve=[&](uint64_t pc)->uint64_t {
        if(pc<original || pc-original>=2*page) return pc;
        return shadows[(pc-original)/page]+(pc&(page-1));
    };
    auto landing=[&](uint64_t pc)->uint64_t {
        if(pc<original || pc-original>=2*page) return 0;
        return shadows[(pc-original)/page]+3*page+(pc&(page-1))*2;
    };
    for (unsigned mode=0;mode<6;++mode) {
        assert(mprotect(mapping,2*page,PROT_READ|PROT_WRITE)==0);
        auto* source=reinterpret_cast<uint32_t*>(original);
        std::fill(source,source+2*page/4,0xd503201f);
        size_t entry=mode==5 ? page/4-4 : 0x200/4;
        source[entry]=0xa9bf7bfd; // stp x29,x30,[sp,#-16]!
        source[entry+1]=0xd2824690; // mov x16,#0x1234
        source[entry+2]=0xd28acf11; // mov x17,#0x5678
        source[entry+3]=0xeb10021f; // cmp x16,x16: Z=1
        size_t helper=page/4;
        uintptr_t call=original+(entry+4)*4, target=original+helper*4;
        if(mode==0) source[entry+4]=branch(call,target)|0x80000000; // BL
        if(mode==1) source[entry+4]=branch(call,target); // B
        if(mode==2) source[entry+4]=conditional(0x54000000,call,target); // B.eq
        if(mode==3) source[entry+4]=conditional(0xb400001f,call,target); // CBZ XZR
        if(mode==4) source[entry+4]=conditional(0x36000010,call,target,14); // TBZ x16,#0
        size_t resume=entry+5;
        if(mode!=5) {
            source[resume]=0xa8c17bfd; // ldp x29,x30,[sp],#16
            source[resume+1]=0xd65f03c0; // ret to host
        }
        size_t i=helper;
        size_t flag_check=i++;
        // A far BL must expose the original return PC; all other transfers
        // preserve the incoming LR saved by the function's own prologue.
        source[i]=mode==0 ? adr(9,original+i*4,call+4) : 0xf94007e9;
        ++i;
        source[i++]=0xeb0903df; // cmp x30,x9
        size_t lr_check=i++;
        source[i++]=0xd2824689; // mov x9,#0x1234
        source[i++]=0xeb09021f; // cmp x16,x9
        size_t x16_check=i++;
        source[i++]=0xd28acf09; // mov x9,#0x5678
        source[i++]=0xeb09023f; // cmp x17,x9
        size_t x17_check=i++;
        source[i++]=0x52800920; // mov w0,#73
        size_t success=i++;
        size_t failure=i;
        source[i++]=0x52800000; // mov w0,#0
        size_t done=i;
        if(mode==5) {
            source[i++]=0xa8c17bfd;
            source[i++]=0xd65f03c0;
        } else source[i]=branch(original+i*4,original+resume*4);
        source[success]=branch(original+success*4,original+done*4);
        for(auto check:{flag_check,lr_check,x16_check,x17_check})
            source[check]=conditional(0x54000001,original+check*4,original+failure*4);
        for(size_t p=0;p<2;++p) {
            std::vector<uint32_t> code;
            assert(macapk::a64::relocate_shadow_page(
                std::span(source+p*page/4,page/4),original+p*page,shadows[p],
                code,nullptr,nullptr,true,true,resolve,landing));
            assert(code.size()*4<=3*page);
            assert(mprotect(reinterpret_cast<void*>(shadows[p]),5*page,PROT_READ|PROT_WRITE)==0);
            std::memcpy(reinterpret_cast<void*>(shadows[p]),code.data(),code.size()*4);
            auto* pads=reinterpret_cast<uint32_t*>(shadows[p]+3*page);
            for(size_t n=0;n<page/4;++n) {
                pads[n*2]=0xa8c17bf0; // restore x16/LR
                pads[n*2+1]=branch(reinterpret_cast<uintptr_t>(&pads[n*2+1]),shadows[p]+n*4);
            }
            sys_icache_invalidate(reinterpret_cast<void*>(shadows[p]),5*page);
            assert(mprotect(reinterpret_cast<void*>(shadows[p]),5*page,PROT_READ|PROT_EXEC)==0);
        }
        assert(mprotect(mapping,2*page,PROT_READ)==0);
        auto probe=reinterpret_cast<int(*)()>(shadows[0]+entry*4);
        for(int n=0;n<1000;++n) assert(probe()==73);
    }
    munmap(mapping,size);
    puts("PASS: far BL/B/B.cond/CBZ/TBZ/fallthrough preserve original LR, scratch registers, flags and stack without traps");
}
