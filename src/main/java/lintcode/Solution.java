package lintcode;

import java.util.Arrays;

class TreeNode {
	public int val;
    public TreeNode left, right;
    public TreeNode(int val) {
        this.val = val;
        this.left = this.right = null;
    }
}



public class Solution {
	/*
	 * @param A: an integer array
	 * @return: A tree node
	 */
	public static TreeNode sortedArrayToBST(int[] A) {
		// write your code here
		int length = A.length;
		if (length == 0) {
			return null;
		}

		TreeNode root = new TreeNode(A[length/2]);
		root.left = findRootNode(Arrays.copyOfRange(A,0,A.length/2));
		root.right = findRootNode(Arrays.copyOfRange(A,A.length/2+1,A.length));
		// while (length/2 > 3) {//while there are 4 or more elements, can be devided
		//     int[] subA1 = Arrays.copyOf(A,length/2);
		// }
		return root;
	}

	public static TreeNode findRootNode(int[] B){

		if (B.length > 1) {
			TreeNode newNode = new TreeNode(B[B.length/2]);

			newNode.left = findRootNode(Arrays.copyOfRange(B,0,B.length/2));
			newNode.right = findRootNode(Arrays.copyOfRange(B,B.length/2+1,B.length));
			return newNode;
		}
		else if (B.length == 0) {
			return null;
		}
		else {
			TreeNode newNode = new TreeNode(B[0]);
			newNode.left = null;
			newNode.right = null;
			return newNode;
		}

		//return the middle element
		//simulate the figure out return's actual position
	}

	public static void main(String[] args) {
		int[] A = {1,2,3,4,5,6,7,8,9,10,11,12,13};
		//int[] A = {1,2,3};
		sortedArrayToBST(A);
	}
}