# system
mock system

# user
## 待复核结论
<<<UNTRUSTED
### id=f_missed01 category=bug severity=important
标题：空列表取 last 越界
失败模式：items 为空时 size()-1 为 -1，get(-1) 抛 IndexOutOfBoundsException
证据：L42: return items.get(items.size() - 1)
>>>
## 代码上下文
文件全文（src/main/java/Foo.java，head 侧）：
class Foo {}
