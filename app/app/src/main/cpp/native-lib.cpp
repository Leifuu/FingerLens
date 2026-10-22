#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <sys/system_properties.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>
#include <dirent.h>
#include <vulkan/vulkan.h>

static std::string b64(const std::string& s) {
    static const char* table="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out; unsigned int val=0; int bits=-6;
    for(unsigned char c:s){ val=(val<<8)|c; bits+=8; while(bits>=0){out+=table[(val>>bits)&63];bits-=6;} }
    if(bits>-6)out+=table[((val<<8)>>(bits+8))&63];
    while(out.size()%4)out+='=';
    return out;
}
static std::string q(const std::string& s) {
    std::string out="\""; const char* hex="0123456789abcdef";
    for(unsigned char c:s){if(c=='\"'||c=='\\'){out+='\\';out+=c;}else if(c<32){out+="\\u00";out+=hex[c>>4];out+=hex[c&15];}else out+=c;}
    return out+"\"";
}
static jstring result(JNIEnv* e,const std::string& s){jbyteArray bytes=e->NewByteArray(s.size());if(!bytes)return nullptr;if(!s.empty())e->SetByteArrayRegion(bytes,0,s.size(),reinterpret_cast<const jbyte*>(s.data()));jclass cls=e->FindClass("java/lang/String");jmethodID ctor=e->GetMethodID(cls,"<init>","([BLjava/lang/String;)V");jstring charset=e->NewStringUTF("UTF-8");auto text=(jstring)e->NewObject(cls,ctor,bytes,charset);e->DeleteLocalRef(bytes);e->DeleteLocalRef(charset);e->DeleteLocalRef(cls);return text;}
static std::string input(JNIEnv* e,jstring s){
    if(!s)return "";
    jclass cls=e->FindClass("java/lang/String");jmethodID method=e->GetMethodID(cls,"getBytes","(Ljava/lang/String;)[B");
    jstring charset=e->NewStringUTF("UTF-8");auto bytes=(jbyteArray)e->CallObjectMethod(s,method,charset);
    jsize len=e->GetArrayLength(bytes);std::string out(len,'\0');if(len>0)e->GetByteArrayRegion(bytes,0,len,reinterpret_cast<jbyte*>(&out[0]));
    e->DeleteLocalRef(bytes);e->DeleteLocalRef(charset);e->DeleteLocalRef(cls);return out;
}
static int parentWithoutLinks(const std::string& path,std::string& leaf){
    if(path.empty()||path[0]!='/'||path.find('\0')!=std::string::npos){errno=EINVAL;return -1;}
    std::vector<std::string> parts;size_t start=1;
    while(start<path.size()){size_t end=path.find('/',start);if(end==std::string::npos)end=path.size();std::string part=path.substr(start,end-start);if(part==".."||part=="."){errno=EINVAL;return -1;}if(!part.empty())parts.push_back(part);start=end+1;}
    int fd=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC);if(fd<0)return -1;
    leaf=parts.empty()?".":parts.back();
    // 父目录只需 search 权限；O_RDONLY 会额外要求读取 /data 等祖先目录。
    for(size_t i=0;i+1<parts.size();i++){int next=openat(fd,parts[i].c_str(),O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);int err=errno;close(fd);if(next<0){errno=err;return -1;}fd=next;}
    return fd;
}
// 文件证据：按字节读取，不清洗 NUL；额外读取一个字节判断是否真的截断。
extern "C" JNIEXPORT jstring JNICALL Java_com_life_fingerprint_NativeProbe_readFile(JNIEnv* e,jclass,jstring path,jint limit){
    if(limit<1||limit>262144)return result(e,"{\"ok\":false,\"errno\":22}");
    std::string p=input(e,path);if(p.find('\0')!=std::string::npos)return result(e,"{\"ok\":false,\"errno\":22}");
    int fd=open(p.c_str(),O_RDONLY|O_CLOEXEC|O_NONBLOCK);if(fd<0)return result(e,"{\"ok\":false,\"errno\":"+std::to_string(errno)+"}");
    std::string out;char buf[4096];int err=0;
    while(out.size()<static_cast<size_t>(limit)+1){ssize_t n=read(fd,buf,std::min(sizeof(buf),static_cast<size_t>(limit)+1-out.size()));if(n<0){if(errno==EINTR)continue;err=errno;break;}if(n==0)break;out.append(buf,n);}
    close(fd);bool truncated=out.size()>static_cast<size_t>(limit);if(truncated)out.resize(limit);
    return result(e,"{\"ok\":"+std::string(err?"false":"true")+",\"errno\":"+std::to_string(err)+",\"bytesRead\":"+std::to_string(out.size())+",\"truncated\":"+(truncated?"true":"false")+",\"base64\":"+q(b64(out))+"}");
}
extern "C" JNIEXPORT jstring JNICALL Java_com_life_fingerprint_NativeProbe_inspectPath(JNIEnv* e,jclass,jstring path){
    std::string p=input(e,path),leaf;struct stat s{};errno=0;int parent=parentWithoutLinks(p,leaf);int rc=parent<0?-1:fstatat(parent,leaf.c_str(),&s,AT_SYMLINK_NOFOLLOW);int err=rc?errno:0;
    std::string out="{\"ok\":"+std::string(rc?"false":"true")+",\"errno\":"+std::to_string(err);
    if(!rc){out+=",\"mode\":"+std::to_string(s.st_mode)+",\"uid\":"+std::to_string(s.st_uid)+",\"gid\":"+std::to_string(s.st_gid)+",\"size\":{\"$type\":\"int64\",\"value\":"+q(std::to_string(s.st_size))+"},\"mtimeSeconds\":"+q(std::to_string(s.st_mtime))+",\"type\":"+q(S_ISLNK(s.st_mode)?"symlink":S_ISDIR(s.st_mode)?"directory":S_ISREG(s.st_mode)?"file":"other");
        if(S_ISLNK(s.st_mode)){char link[4097];ssize_t n=readlinkat(parent,leaf.c_str(),link,4096);out+=",\"linkErrno\":"+std::to_string(n<0?errno:0);if(n>=0)out+=",\"linkBase64\":"+q(b64(std::string(link,n)))+",\"linkTruncated\":"+(n==4096?"true":"false");}}
    if(parent>=0)close(parent);
    return result(e,out+"}");
}
// 有界 readdir 避免 File.listFiles 一次分配整个巨大目录；保留目录返回顺序及原始名称字节。
extern "C" JNIEXPORT jstring JNICALL Java_com_life_fingerprint_NativeProbe_listDirectory(JNIEnv* e,jclass,jstring path,jint limit){
    if(limit<1||limit>100000)return result(e,"{\"ok\":false,\"errno\":22,\"names\":[]}");
    std::string p=input(e,path),leaf;int parent=parentWithoutLinks(p,leaf);if(parent<0)return result(e,"{\"ok\":false,\"errno\":"+std::to_string(errno)+",\"names\":[]}");int fd=openat(parent,leaf.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);int openErrno=errno;close(parent);if(fd<0)return result(e,"{\"ok\":false,\"errno\":"+std::to_string(openErrno)+",\"names\":[]}");DIR* directory=fdopendir(fd);if(!directory){int err=errno;close(fd);return result(e,"{\"ok\":false,\"errno\":"+std::to_string(err)+",\"names\":[]}");}
    std::string names="[";int count=0,err=0;bool truncated=false;
    while(true){errno=0;dirent* entry=readdir(directory);if(!entry){err=errno;break;}std::string name=entry->d_name;if(name=="."||name=="..")continue;if(count>=limit){truncated=true;break;}if(count++)names+=",";names+=q(b64(name));}
    closedir(directory);return result(e,"{\"ok\":"+std::string(err?"false":"true")+",\"errno\":"+std::to_string(err)+",\"truncated\":"+(truncated?"true":"false")+",\"names\":"+names+"]}");
}
static void property(const prop_info* pi,void* cookie){
    auto* out=static_cast<std::string*>(cookie);
    using ReadCallback=void(*)(const prop_info*,void(*)(void*,const char*,const char*,uint32_t),void*);
    auto readCallback=reinterpret_cast<ReadCallback>(dlsym(RTLD_DEFAULT,"__system_property_read_callback"));
    if(readCallback)readCallback(pi,[](void* c,const char* name,const char* value,uint32_t serial){auto* o=static_cast<std::string*>(c);if(o->size()>1)*o+=",";*o+="{\"nameBase64\":"+q(b64(name))+",\"valueBase64\":"+q(b64(value))+",\"serial\":"+std::to_string(serial)+"}";},out);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_life_fingerprint_NativeProbe_properties(JNIEnv* e,jclass){std::string out="[";__system_property_foreach(property,&out);return result(e,out+"]");}
extern "C" JNIEXPORT jstring JNICALL Java_com_life_fingerprint_NativeProbe_kernel(JNIEnv* e,jclass){struct utsname u{};if(uname(&u))return result(e,"{\"errno\":"+std::to_string(errno)+"}");return result(e,"{\"sysname\":"+q(u.sysname)+",\"nodename\":"+q(u.nodename)+",\"release\":"+q(u.release)+",\"version\":"+q(u.version)+",\"machine\":"+q(u.machine)+"}");}
// 图形能力只建离屏上下文；每个返回码独立保留，不用声明特性代替初始化结果。
extern "C" JNIEXPORT jstring JNICALL Java_com_life_fingerprint_NativeProbe_graphics(JNIEnv* e,jclass){
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);EGLint major=0,minor=0;bool initialized=d!=EGL_NO_DISPLAY&&eglInitialize(d,&major,&minor);
    std::string out="{\"eglInitialized\":"+std::string(initialized?"true":"false")+",\"eglError\":"+std::to_string(eglGetError());
    if(initialized){const char* vendor=eglQueryString(d,EGL_VENDOR);const char* version=eglQueryString(d,EGL_VERSION);const char* ext=eglQueryString(d,EGL_EXTENSIONS);out+=",\"eglVendor\":"+q(vendor?vendor:"")+",\"eglVersion\":"+q(version?version:"")+",\"eglExtensions\":"+q(ext?ext:"");
        EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};EGLConfig cfg;EGLint count=0;
        if(eglChooseConfig(d,attrs,&cfg,1,&count)&&count){EGLint surfaceAttrs[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};EGLint contextAttrs[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};EGLSurface s=eglCreatePbufferSurface(d,cfg,surfaceAttrs);EGLContext c=eglCreateContext(d,cfg,EGL_NO_CONTEXT,contextAttrs);
            if(s!=EGL_NO_SURFACE&&c!=EGL_NO_CONTEXT&&eglMakeCurrent(d,s,s,c)){for(auto field:std::vector<std::pair<std::string,GLenum>>{{"glVendor",GL_VENDOR},{"glRenderer",GL_RENDERER},{"glVersion",GL_VERSION},{"glExtensions",GL_EXTENSIONS}}){const auto* v=glGetString(field.second);out+=","+q(field.first)+":"+q(v?reinterpret_cast<const char*>(v):"");}}
            eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(c!=EGL_NO_CONTEXT)eglDestroyContext(d,c);if(s!=EGL_NO_SURFACE)eglDestroySurface(d,s);}
        eglTerminate(d);}
    void* lib=dlopen("libvulkan.so",RTLD_NOW|RTLD_LOCAL);out+=",\"vulkanLibraryLoaded\":"+std::string(lib?"true":"false");
    if(lib){auto enumerate=reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(dlsym(lib,"vkEnumerateInstanceExtensionProperties"));if(enumerate){uint32_t count=0;VkResult rc=enumerate(nullptr,&count,nullptr);out+=",\"vulkanExtensionResult\":"+std::to_string(rc);if(rc==VK_SUCCESS&&count<=4096){std::vector<VkExtensionProperties> props(count);rc=enumerate(nullptr,&count,props.data());out+=",\"vulkanExtensions\":[";for(uint32_t i=0;i<count&&i<props.size();i++){if(i)out+=",";out+="{\"name\":"+q(props[i].extensionName)+",\"specVersion\":"+std::to_string(props[i].specVersion)+"}";}out+="]";}}dlclose(lib);}
    return result(e,out+"}");
}
