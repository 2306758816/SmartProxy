package me.smartproxy.core;

import android.annotation.SuppressLint;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class SocketChannelWrapper {

	final static ByteBuffer GL_BUFFER=ByteBuffer.allocate(10000);
	public static long SessionCount;
 
	SocketChannel m_InnerChannel;
	ByteBuffer m_SendRemainBuffer;
	Selector m_Selector;
	SocketChannelWrapper m_BrotherChannelWrapper;
	InetSocketAddress m_TargetSocketAddress;
	boolean m_Disposed;
	boolean m_UseProxy;
	boolean m_TunnelEstablished;
	String m_ID;
 
	public SocketChannelWrapper(SocketChannel innerChannel,Selector selector){
		this.m_InnerChannel=innerChannel;
		this.m_Selector=selector;
		SessionCount++;
	}
	
	public String toString(){
		return m_ID;
	}
	
	public static SocketChannelWrapper createNew(Selector selector) throws Exception{
		SocketChannel innerChannel=null;
		try {
			innerChannel=SocketChannel.open();
			innerChannel.configureBlocking(false);
			return new SocketChannelWrapper(innerChannel, selector);
		} catch (Exception e) {
			if(innerChannel!=null){
				innerChannel.close();
			}
			throw e;
		}
	}
	
	public void setBrotherChannelWrapper(SocketChannelWrapper brotherChannelWrapper){
		m_BrotherChannelWrapper=brotherChannelWrapper;
	}
	
	@SuppressLint("DefaultLocale")
	public void connect(InetSocketAddress targetSocketAddress,InetSocketAddress proxySocketAddress) throws Exception{
		if(LocalVpnService.Instance.protect(m_InnerChannel.socket())){//����socket����vpn
			m_TargetSocketAddress=targetSocketAddress;
			String id=String.format("%d%s", m_BrotherChannelWrapper.m_InnerChannel.socket().getPort(),targetSocketAddress);
			m_ID="[R]"+id;
			m_BrotherChannelWrapper.m_ID="[L]"+id;
			
			m_InnerChannel.register(m_Selector, SelectionKey.OP_CONNECT,this);//ע�������¼�
			if(proxySocketAddress!=null){
				m_UseProxy=true;
				m_BrotherChannelWrapper.m_UseProxy=true;
				m_InnerChannel.connect(proxySocketAddress);//���Ӵ���
			}else {
				m_UseProxy=false;
				m_BrotherChannelWrapper.m_UseProxy=false;
				m_InnerChannel.connect(targetSocketAddress);//ֱ��
			}
		}else {
			throw new Exception("VPN protect socket failed.");
		}
	}
  
	void registerReadOperation() throws Exception{
		if(m_InnerChannel.isBlocking()){
			m_InnerChannel.configureBlocking(false);
		}
		m_InnerChannel.register(m_Selector, SelectionKey.OP_READ,this);//ע����¼�
	}
	
	void trySendPartOfHeader(ByteBuffer buffer)  throws Exception {
		int bytesSent=0;
		if(m_UseProxy&&m_TunnelEstablished&& m_TargetSocketAddress!=null&&m_TargetSocketAddress.getPort()!=443&&buffer.remaining()>10){
			int pos=buffer.position()+buffer.arrayOffset();
    		String firString=new String(buffer.array(),pos,10).toUpperCase();
    		if(firString.startsWith("GET /") || firString.startsWith("POST /")){
    			int limit=buffer.limit();
    			buffer.limit(buffer.position()+10);
    			bytesSent=m_InnerChannel.write(buffer);
    			buffer.limit(limit);
    			if(ProxyConfig.IS_DEBUG)
    				System.out.printf("Send %d bytes(%s) to %s\n",bytesSent,firString,m_TargetSocketAddress);
    		}
		}
	}
	
    boolean write(ByteBuffer buffer,boolean copyRemainData) throws Exception {
    	
    	if(ProxyConfig.Instance.isIsolateHttpHostHeader()){
    		trySendPartOfHeader(buffer);//���Է�������ͷ��һ���֣�������ͷ��host�ڵڶ��������淢�ͣ��Ӷ��ƹ������İ��������ơ�
    	}
    	
    	int bytesSent;
    	while (buffer.hasRemaining()) {
			bytesSent=m_InnerChannel.write(buffer);
			if(bytesSent==0){
				break;//�����ٷ����ˣ���ֹѭ��
			}
		}
    	
    	if(buffer.hasRemaining()){//����û�з������
    		if(copyRemainData){//����ʣ�����ݣ�Ȼ������д���¼�������д��ʱд�롣
    			if(m_SendRemainBuffer==null){
    				m_SendRemainBuffer=ByteBuffer.allocate(buffer.capacity());
    			}
    			
    			//����ʣ������
        		m_SendRemainBuffer.clear();
        		m_SendRemainBuffer.put(buffer);
    			m_SendRemainBuffer.flip();
    			
    			m_InnerChannel.register(m_Selector,SelectionKey.OP_WRITE, this);//ע��д�¼�
    		}
			return false;
    	}
    	else {//���������
    		return true;
		}
	}
 
    void onTunnelEstablished() throws Exception{
    	m_TunnelEstablished=true;
		m_BrotherChannelWrapper.m_TunnelEstablished=true;
		this.registerReadOperation();//��ʼ��������
		m_BrotherChannelWrapper.registerReadOperation();//�ֵ�Ҳ��ʼ�����ݰ�
    }
    
    @SuppressLint("DefaultLocale")
	public void onConnected(){
    	try {
    		ByteBuffer buffer=GL_BUFFER;
        	if(m_InnerChannel.finishConnect()){//���ӳɹ�
        		if(m_UseProxy){//ʹ�ô���
        			String request = String.format("CONNECT %s:%d HTTP/1.0\r\nProxy-Connection: keep-alive\r\nUser-Agent: %s\r\nX-App-Install-ID: %s\r\n\r\n", 
        					m_TargetSocketAddress.getHostName(),
        					m_TargetSocketAddress.getPort(),
        					ProxyConfig.Instance.getUserAgent(),
        					ProxyConfig.AppInstallID);
        			
        			buffer.clear();
        			buffer.put(request.getBytes());
        			buffer.flip();
        			if(this.write(buffer,true)){//�����������󵽴��������
        				this.registerReadOperation();//��ʼ���մ����������Ӧ����
        			}
        		}else {//ֱ��
        			onTunnelEstablished();//��ʼ��������
				}
        	}else {//����ʧ��
        		System.out.printf("%s connect failed.\n", m_ID);
        		LocalVpnService.Instance.writeLog("Error: connect to %s failed.", m_UseProxy?"proxy":"server");
				this.dispose();
			}
		} catch (Exception e) {
			System.out.printf("%s connect error: %s.\n", m_ID,e);
			LocalVpnService.Instance.writeLog("Error: connect to %s failed: %s", m_UseProxy?"proxy":"server",e);
			this.dispose();
		}
    }
    
	public void onRead(SelectionKey key){
		try {
			ByteBuffer buffer=GL_BUFFER;
			buffer.clear();
			int bytesRead=m_InnerChannel.read(buffer);
			if(bytesRead>0){
				if(m_TunnelEstablished){
					//�����������ݣ�ת�����ֵܡ�
					buffer.flip();
					if(!m_BrotherChannelWrapper.write(buffer,true)){
						key.cancel();//�ֵܳԲ�������ȡ����ȡ�¼���
						if(ProxyConfig.IS_DEBUG)
							System.out.printf("%s can not read more.\n", m_ID);
					}
				}else {
					//������Լ��
					//��������ѽ���
					onTunnelEstablished();//��ʼ��������
				}
			}else if(bytesRead<0) {
				this.dispose();//�����ѹرգ��ͷ���Դ��
			}
		} catch (Exception e) {
			this.dispose();
		}
	}

	public void onWrite(SelectionKey key){
		try {
			if(this.write(m_SendRemainBuffer, false)) {//���ʣ�������Ѿ��������
				key.cancel();//ȡ��д�¼���
				if(m_TunnelEstablished){
					m_BrotherChannelWrapper.registerReadOperation();//������ݷ�����ϣ�֪ͨ�ֵܿ����������ˡ�
				}else {
					this.registerReadOperation();//��ʼ���մ����������Ӧ����
				}
			}
		} catch (Exception e) {
			this.dispose();
		}
	}
	
	public void dispose(){
		disposeInternal(true);
	}
	
	void disposeInternal(boolean disposeBrother) {
		if(m_Disposed){
			return;
		}
		else {
			try {
				m_InnerChannel.close();
			} catch (Exception e) {
			}
			
			if(m_BrotherChannelWrapper!=null&&disposeBrother){
				m_BrotherChannelWrapper.disposeInternal(false);//���ֵܵ���ԴҲ�ͷ��ˡ�
			}

			m_InnerChannel=null;
		    m_SendRemainBuffer=null;
			m_Selector=null;
			m_BrotherChannelWrapper=null;
			m_TargetSocketAddress=null;
			m_Disposed=true;
			SessionCount--;
		}
	}
}
