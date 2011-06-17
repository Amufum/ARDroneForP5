/*
 *
  Copyright (c) <2011>, <Shigeo Yoshida>
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
The names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF 
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, 
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package newardrone;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.StringTokenizer;

import newardrone.navdata.ARDroneNav;
import newardrone.navdata.DroneState;
import newardrone.navdata.listener.AttitudeListener;
import newardrone.navdata.listener.BatteryListener;
import newardrone.navdata.listener.StateListener;
import newardrone.navdata.listener.VelocityListener;
import newardrone.video.ARDroneVideo;
import newardrone.video.ImageListener;

public class ARDroneCtrl {

	/** default IP Address */
	private static final String IP_ADDRESS="192.168.1.1";
	private static final String CR="\r";

	/** for video stream */
	private ARDroneVideo ardroneVideo=null;
	/** image from video streaming */
	//private BufferedImage videoImage=null;
	/** for nav data stream */
	private ARDroneNav ardroneNav=null;

	/** socket for AT command  */
	private DatagramSocket socket=null;
	/** sequence number */
	private static int seq=1;

	/** thread for send command */
	private PacketSendThread pst=null;

	private InetAddress inetaddr=null;

	/** ARDrone speed */
	private float speed=(float) 0.1;

	/** angle */
	private float pitch=0;
	private float roll=0;
	private float yaw=0;
	private float gaz=0;

	private FloatBuffer fb=null;
	private IntBuffer ib=null;

	private boolean landing=true;

	//listeners
	private AttitudeListener attitudeListener=null;
	private BatteryListener batteryListener=null;
	private StateListener stateListener=null;
	private VelocityListener velocityListener=null;
	private ImageListener imageListener=null;

	/**
	 * constructor
	 */
	public ARDroneCtrl(){
		initialize();
	}

	/**
	 * initializer
	 */
	private void initialize(){

	}

	/**
	 * connection
	 * @return
	 */
	public boolean connect(){
		return connect(IP_ADDRESS);
	}
	/**
	 * connection
	 * @param ipaddr
	 * @return
	 */
	public boolean connect(String ipaddr){
		StringTokenizer st=new StringTokenizer(ipaddr, ".");

		byte[] ip_bytes=new byte[4];
		if(st.countTokens()==4){
			for(int i=0; i<4; i++){
				ip_bytes[i]=(byte) Integer.parseInt(st.nextToken());
			}
		}else{
			System.out.println("Incorrect IP address format: "+ipaddr);
			return false;
		}

		System.out.println("IP: "+ipaddr);
		System.out.println("Speed: "+ speed);

		ByteBuffer bb=ByteBuffer.allocate(4);
		fb=bb.asFloatBuffer();
		ib=bb.asIntBuffer();

		try {
			inetaddr=InetAddress.getByAddress(ip_bytes);
			socket=new DatagramSocket(5556);
			socket.setSoTimeout(3000);

			pst=new PacketSendThread();

		} catch (UnknownHostException e) {
			e.printStackTrace();
			return false;
		} catch (SocketException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	/**
	 * if you want to get video data,
	 * you should call this method
	 * @return
	 */
	public boolean connectVideo(){
		if(inetaddr==null){
			System.err.println("please call \"connect\" method, before calling this method");
			return false;
		}		
		if(!landing){
			System.err.println("calling this method not in flight");
			return false;			
		}

		ardroneVideo=new ARDroneVideo(this, inetaddr);
		ardroneVideo.setImageListener(new ImageListener() {
			@Override
			public void imageUpdated(BufferedImage image) {
				if(imageListener!=null){
					imageListener.imageUpdated(image);
				}
			}
		});
		return true;
	}

	/**
	 * if you want to get nav data,
	 * you should call this method
	 * @return
	 */
	public boolean connectNav(){
		if(inetaddr==null){
			System.err.println("please call \"connect\" method, before calling this method");
			return false;
		}
		if(!landing){
			System.err.println("calling this method not in flight");
			return false;			
		}

		ardroneNav=new ARDroneNav(this, inetaddr);
		ardroneNav.setAttitudeListener(new AttitudeListener() {
			@Override
			public void attitudeUpdated(float pitch, float roll, float yaw, int altitude) {
				if(attitudeListener!=null){
					attitudeListener.attitudeUpdated(pitch, roll, yaw, altitude);
				}
			}
		});
		ardroneNav.setBatteryListener(new BatteryListener() {
			@Override
			public void batteryLevelChanged(int percentage) {
				if(batteryListener!=null){
					batteryListener.batteryLevelChanged(percentage);
				}
			}
		});
		ardroneNav.setStateListener(new StateListener() {
			@Override
			public void stateChanged(DroneState state) {
				if(stateListener!=null){
					stateListener.stateChanged(state);
				}
			}
		});
		ardroneNav.setVelocityListener(new VelocityListener() {
			@Override
			public void velocityChanged(float vx, float vy, float vz) {
				if(velocityListener!=null){
					velocityListener.velocityChanged(vx, vy, vz);
				}
			}
		});

		return true;
	}

	/**
	 * only called from ARDroneNav
	 * send AT*CONFIG=sequence number,"general:navdata_demo","TRUE"
	 */
	public void enableDemoData(){
		pst.setATCommand("AT*CONFIG="+(seq++)+",\"general:navdata_demo\",\"TRUE\""+CR+"AT*FTRIM="+(seq++), false);
	}

	/**
	 * only called from ARDroneVideo
	 * send AT*CONFIG=sequence number,"general:video_enable","TRUE"
	 */
	public void enableVideoData(){
		pst.setATCommand("AT*CONFIG="+(seq++)+",\"general:video_enable\",\"TRUE\""+CR+"AT*FTRIM="+(seq++), false);
	}

	/**
	 * send Ack
	 * AT*CTRL=sequence number,0
	 */
	public void sendControlAck(){
		pst.setATCommand("AT*CTRL="+(seq++)+",0", false);
	}







	/**
	 * Broadcast video from the front camera
	 */
	public void setFrontCameraStreaming(){//hori
		pst.setATCommand("AT*ZAP="+(seq++)+",0", false);//correct
	}
	/**
	 * Broadcast video from the belly camera, showing the ground
	 */
	public void setBellyCameraStreaming(){//vert
		pst.setATCommand("AT*ZAP="+(seq++)+",1", false);		
	}
	/**
	 * Broadcast video from the front camera, 
	 * with the belly camera encrusted in the top-left corner
	 */
	public void setFrontCameraWithSmallBellyStreaming(){//large hori, small vert
		pst.setATCommand("AT*ZAP="+(seq++)+",2", false);//correct
	}
	/**
	 * Broadcast video from the belly camera,
	 * with the front camera picture encrusted in the top-left corner
	 */
	public void setBellyCameraWithSmallFrontStreaming(){//large vert, small hori
		pst.setATCommand("AT*ZAP="+(seq++)+",3", false);
	}
	/**
	 * Switch to the next possible camera combination
	 */
	public void setNextCamera(){//next possible camera
		pst.setATCommand("AT*ZAP="+(seq++)+",4", false);
	}

	//public void setCamera(int num){
	//if(num<0)
	//num=0;
	//pst.setATCommand("AT*ZAP="+(seq++)+","+num, false);		
	//}







	//update listeners
	public void addImageUpdateListener(ImageListener imageListener){
		this.imageListener=imageListener;
	}
	public void addAttitudeUpdateListener(AttitudeListener attitudeListener){
		this.attitudeListener=attitudeListener;
	}
	public void addBatteryUpdateListener(BatteryListener batteryListener){
		this.batteryListener=batteryListener;
	}
	public void addStateUpdateListener(StateListener stateListener){
		this.stateListener=stateListener;
	}
	public void addVelocityUpdateListener(VelocityListener velocityListener){
		this.velocityListener=velocityListener;
	}
	//remove listeners
	public void removeImageUpdateListener(){
		imageListener=null;
	}
	public void removeAttitudeUpdateListener(){
		attitudeListener=null;
	}
	public void removeBatteryUpdateListener(){
		batteryListener=null;
	}
	public void removeStateUpdateListener(){
		stateListener=null;
	}
	public void removeVelocityUpdateListener(){
		velocityListener=null;
	}

	/**
	 * disconnection
	 */
	public void disconnect(){
		stop();
		landing();
		pst=null;
		socket.close();

		if(ardroneNav!=null){
			ardroneNav.close();
			ardroneNav=null;
		}
		if(ardroneVideo!=null){
			ardroneVideo.close();
			ardroneVideo=null;
		}
	}

	/**
	 * start threads
	 * if you want to control ARDrone, you have to call this method.
	 */
	public void start(){
		//start control thread
		pst.start();
		//start navdata thread
		if(ardroneNav!=null)
			ardroneNav.start();
		//start video thread
		if(ardroneVideo!=null)
			ardroneVideo.start();

		System.out.println("thread start!!!!");
	}

	/**
	 * landing
	 */
	public void landing(){
		pst.setATCommand("AT*REF=" + (seq++) + ",290717696", false);
		landing=true;
	}

	/**
	 * take off
	 */
	public void takeOff(){
		pst.setATCommand("AT*REF=" + (seq++) + ",290718208", false);
		landing=false;
	}

	/**
	 * backward
	 * pitch-
	 */
	public void backward() {
		//pst.setATCommand("AT*PCMD=" + (seq++) + ",1," + intOfFloat(-speed) + ",0,0,0", true);
		pst.setATCommand("AT*PCMD="+(seq++)+",1,0,"+intOfFloat(speed)+",0,0"+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * backward with specified speed
	 * @param speed
	 */
	public void backward(int speed){
		setSpeed(speed);
		backward();
	}

	/**
	 * forward
	 * pitch+
	 */
	public void forward() {
		//pst.setATCommand("AT*PCMD="+(seq++)+",1,0,"+(-1082130432)+",0,0"+"\r"+"AT*REF=" + (seq++) + ",290718208", true);	
		pst.setATCommand("AT*PCMD="+(seq++)+",1,0,"+intOfFloat(-speed)+",0,0"+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * forward with specified speed
	 * @param speed
	 */
	public void forward(int speed){
		setSpeed(speed);
		forward();
	}

	/**
	 * ccw
	 * yaw-
	 */
	public void spinLeft() {
		pst.setATCommand("AT*PCMD=" + (seq++) + ",1,0,0,0," + intOfFloat(-speed)+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * ccw with specified speed
	 * @param speed
	 */
	public void spinLeft(int speed){
		setSpeed(speed);
		spinLeft();
	}

	/**
	 * cw
	 * yaw+
	 */
	public void spinRight() {
		pst.setATCommand("AT*PCMD=" + (seq++) + ",1,0,0,0," + intOfFloat(speed)+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * cw with specified speed
	 * @param speed
	 */
	public void spinRight(int speed){
		setSpeed(speed);
		spinRight();
	}

	/**
	 * hovering in the air
	 */
	public void stop() {
		pst.setATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0", true);
	}

	/**
	 * get current speed
	 */
	public int getSpeed() {
		return (int)(speed*100);
	}

	/**
	 * set speed
	 * speed:0-100%
	 */
	public void setSpeed(int speed) {
		if(speed>100)
			speed=100;
		else if(speed<0)
			speed=0;

		this.speed=(float) (speed/100.0);
	}

	/**
	 * down
	 * gaz-
	 */
	public void down(){
		pitch = 0;
		roll = 0;
		gaz = -speed;
		yaw = 0;
		pst.setATCommand("AT*PCMD=" + (seq++) + ",1," + intOfFloat(pitch) + "," + intOfFloat(roll)
				+ "," + intOfFloat(gaz) + "," + intOfFloat(yaw)+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * move down with specified speed
	 * @param speed
	 */
	public void down(int speed){
		setSpeed(speed);
		down();
	}

	/**
	 * up
	 * gaz+
	 */
	public void up(){
		pitch = 0; 
		roll = 0; 
		gaz = speed;
		yaw = 0;
		pst.setATCommand("AT*PCMD=" + (seq++) + ",1," + intOfFloat(pitch) + "," + intOfFloat(roll)
				+ "," + intOfFloat(gaz) + "," + intOfFloat(yaw)+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * move up with specified speed
	 * @param speed
	 */
	public void up(int speed){
		setSpeed(speed);
		up();
	}

	/**
	 * move to left
	 * roll-
	 */
	public void goLeft(){
		//pst.setATCommand("AT*PCMD=" + (seq++) + ",1,0," + intOfFloat(-speed) + ",0,0", true);
		pst.setATCommand("AT*PCMD="+(seq++)+",1,"+intOfFloat(-speed)+",0,0,0"+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * move left with specified speed
	 * @param speed
	 */
	public void goLeft(int speed){
		setSpeed(speed);
		goLeft();
	}

	/**
	 * move to right
	 * roll+
	 */
	public void goRight(){
		//pst.setATCommand("AT*PCMD=" + (seq++) + ",1,0," + intOfFloat(speed) + ",0,0", true);
		pst.setATCommand("AT*PCMD="+(seq++)+",1,"+intOfFloat(speed)+",0,0,0"+"\r"+"AT*REF=" + (seq++) + ",290718208", true);
	}
	/**
	 * move right with specified speed
	 * @param speed
	 */
	public void goRight(int speed){
		setSpeed(speed);
		goRight();
	}

	private int intOfFloat(float f) {
		fb.put(0, f);
		return ib.get(0);
	}	

	/**
	 * not implemented 
	 * @param radius
	 */
	public void turnLeft(int radius) {
	}

	/**
	 * not implemented
	 * @param radius
	 */
	public void turnRight(int radius) {
	}


	/**
	 * for socket
	 * @author shigeo
	 *
	 */
	private class PacketSendThread extends Thread{

		//private int thCount=0;

		private boolean continuance=false;
		private String atCommand=null;

		private boolean init=false;
		private ArrayList<String> initCommands=null;

		public PacketSendThread(){
			initialize();
		}

		private void initialize(){
			init=true;
			initCommands=new ArrayList<String>();
			initCommands.add("AT*CONFIG="+(seq++)+",\"general:navdata_demo\",\"TRUE\""+CR+"AT*FTRIM="+(seq++));//1
			//initCommands.add("AT*CONFIG="+(seq++)+",\"general:navdata_demo\",\"TRUE\"");//1
			initCommands.add("AT*PMODE="+(seq++)+",2"+CR+"AT*MISC="+(seq++)+",2,20,2000,3000"+CR+"AT*FTRIM="+(seq++)+CR+"AT*REF="+(seq++)+",290717696");//2-5
			initCommands.add("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696"+CR+"AT*COMWDG="+(seq++));//6-8
			initCommands.add("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696"+CR+"AT*COMWDG="+(seq++));//6-8
			//initCommands.add("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696");
			//initCommands.add("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696");//11-12
			//initCommands.add("AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*REF="+(seq++)+",290717696"+CR+"AT*COMWDG="+(seq++));//13-23
			//initCommands.add("AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696");//24-34
			//initCommands.add("AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*REF="+(seq++)+",290717696");//35-37
			//initCommands.add("AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CONFIG="+(seq++)+",\"general:navdata_demo\",\"FALSE\"");//38-41
			//initCommands.add("AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696");//42-49
			//initCommands.add("AT*CTRL="+(seq++)+",5,0"+CR+"AT*CTRL="+(seq++)+",5,0"+CR+"AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696");//50-53
		}

		/**
		 * AR.Drone�ɃR�}���h�𑗂�
		 * Send command to ARDrone
		 * @param atCommand
		 * @return
		 */
		private synchronized boolean sendATCommand(String atCommand){
			//System.out.println("AT command:\n"+atCommand);
			//System.out.println("Speed: "+this.getSpeed());
			byte[] buffer=(atCommand+CR).getBytes();
			DatagramPacket packet=new DatagramPacket(buffer, buffer.length, inetaddr, 5556);
			try {
				socket.send(packet);
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
			return true;
		}

		/**
		 * AR.Drone�ɑ���R�}���h��ێ�����
		 * Hold command temporary 
		 * @param atCommand
		 * @param continuance
		 */
		public void setATCommand(String atCommand, boolean continuance){
			this.atCommand=atCommand;
			this.continuance=continuance;
		}

		/**
		 * AR.Drone�ɃR�}���h��20ms���ɑ���
		 * 
		 */
		public void run(){
			while(true){
				try {
					if(init){//AR.Drone�̏����ݒ������B�����ݒ蒆�͏����ݒ�ȊO�̃R�}���h�͑���Ȃ��B
						if(initCommands.size()==0){
							init=false;
							initCommands=null;
							System.out.println("initialize completed!!!!");
							continue;
						}
						sendATCommand(initCommands.remove(0));
						sleep(20);
						continue;
					}

					//continuance��true�̏ꍇ�͎��̖��߂������Ă���܂ŁA
					//���̖��߂𑗂葱����B
					if(this.atCommand!=null){
						sendATCommand(this.atCommand);
						sleep(20);
						if(!this.continuance){
							this.atCommand=null;
							continue;
						}
						//thCount=0;
						continue;
					}
					/*thCount++;
					if(thCount==12){
						for(int i=0; i<5; i++){
							sendATCommand("AT*COMWDG="+(seq++));
							sleep(20);
						}
					}*/

					/*if(landing){//������Ԃ̏ꍇ��AT*REF�͒���
						sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696");
						sleep(20);
						sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696"+CR+"AT*COMWDG="+(seq++));
						sleep(20);						
					}else{//������Ԃ̏ꍇ��AT*REF�͗���
						sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290718208");
						sleep(20);
						//sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+CR+"AT*COMWDG="+(seq++));
						//sleep(20);
					}*/
					if(landing){//������Ԃ̏ꍇ��AT*REF�͒���
						sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696");
						sleep(20);
						/*if((thCount++)==5){//240ms
							//sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290717696"+CR+"AT*COMWDG="+(seq++));
							for(int i=0; i<5; i++){
								sendATCommand("AT*COMWDG="+(seq++));
								sleep(20);
							}
							thCount=0;
						}*/
					}else{//������Ԃ̏ꍇ��AT*REF�͗���
						sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290718208");
						sleep(20);
						/*if((thCount++)==5){
							//sendATCommand("AT*PCMD="+(seq++)+",1,0,0,0,0"+CR+"AT*REF="+(seq++)+",290718208"+CR+"AT*COMWDG="+(seq++));
							for(int i=0; i<5; i++){
								sendATCommand("AT*COMWDG="+(seq++));
								sleep(20);
							}
							thCount=0;
						}*/
					}
					//printARDroneInfo();

				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}	
}